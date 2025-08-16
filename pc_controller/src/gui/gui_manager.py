"""GUI Manager for the PC Controller (Hub) - Phase 3.

Implements the main QMainWindow with a tabbed interface (Dashboard, Logs),
including a dynamic grid of device widgets and live visualization for local
webcam (video) and Shimmer GSR (plot). This module uses non-blocking UI
updates via QTimer and PyQt signals, and delegates device access to the
core local interfaces that optionally use native C++ backends via PyBind11.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Optional, Tuple
import logging
import os
import time

import numpy as np
from PyQt6.QtCore import QObject, QTimer, Qt, pyqtSignal, pyqtSlot
from PyQt6.QtGui import QAction, QImage, QPixmap
from PyQt6.QtWidgets import (
    QGridLayout,
    QLabel,
    QMainWindow,
    QPushButton,
    QTabWidget,
    QTextEdit,
    QToolBar,
    QVBoxLayout,
    QWidget,
)

try:
    import pyqtgraph as pg
except Exception:  # pragma: no cover - import guard for environments without Qt backend
    pg = None

from network.network_controller import DiscoveredDevice, NetworkController

# Local device interfaces (Python shim that optionally uses native backends)
try:
    from core.local_interfaces import ShimmerInterface, WebcamInterface
except Exception:  # pragma: no cover - in case of import issues during tests
    ShimmerInterface = None  # type: ignore
    WebcamInterface = None  # type: ignore


@dataclass
class _GridPos:
    row: int
    col: int


class DeviceWidget(QWidget):
    """Reusable widget for a single data source.

    Two modes:
    - video: displays frames in a QLabel
    - gsr: displays a scrolling waveform using PyQtGraph
    """

    def __init__(self, kind: str, title: str, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.kind = kind
        self.title = title
        self.setObjectName(f"DeviceWidget::{kind}::{title}")
        layout = QVBoxLayout(self)
        self.header = QLabel(title, self)
        self.header.setAlignment(Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(self.header)

        if kind == "video":
            self.view = QLabel(self)
            self.view.setAlignment(Qt.AlignmentFlag.AlignCenter)
            self.view.setMinimumSize(320, 180)
            layout.addWidget(self.view)
        elif kind == "gsr":
            if pg is None:
                lbl = QLabel("PyQtGraph not available", self)
                lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
                layout.addWidget(lbl)
                self.view = None
            else:
                self.view = pg.PlotWidget(self)
                self.view.setBackground("w")
                self.curve = self.view.plot(pen=pg.mkPen(color=(0, 120, 255), width=2))
                layout.addWidget(self.view)
            # Data buffer for plotting: last 10 seconds at 128 Hz
            self._buf_seconds = 10.0
            self._buf_max = int(128 * self._buf_seconds)
            self._times: deque[float] = deque(maxlen=self._buf_max)
            self._values: deque[float] = deque(maxlen=self._buf_max)
        else:
            raise ValueError(f"Unsupported DeviceWidget kind: {kind}")

    def update_video_frame(self, frame_bgr: np.ndarray) -> None:
        if self.kind != "video":
            return
        if frame_bgr is None:
            return
        # Convert BGR to RGB for QImage
        if frame_bgr.ndim == 3 and frame_bgr.shape[2] == 3:
            frame_rgb = frame_bgr[:, :, ::-1].copy()
            h, w, ch = frame_rgb.shape
            bytes_per_line = ch * w
            qimg = QImage(frame_rgb.data, w, h, bytes_per_line, QImage.Format.Format_RGB888)
            self.view.setPixmap(QPixmap.fromImage(qimg).scaled(
                self.view.size(), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation
            ))

    def append_gsr_samples(self, ts: np.ndarray, vals: np.ndarray) -> None:
        if self.kind != "gsr" or pg is None or self.view is None:
            return
        if ts.size == 0:
            return
        self._times.extend(ts.tolist())
        self._values.extend(vals.tolist())
        # Update plot immediately; X axis as relative seconds
        t0 = self._times[0] if self._times else time.monotonic()
        x = np.fromiter((t - t0 for t in self._times), dtype=np.float64, count=len(self._times))
        y = np.fromiter(self._values, dtype=np.float64, count=len(self._values))
        self.curve.setData(x, y)


class GUIManager(QMainWindow):
    """Phase 3 GUI Manager implementing Dashboard and Logs with live local sensors."""

    # Signals for internal logging from timers/threads
    ui_log = pyqtSignal(str)

    def __init__(self, network: NetworkController) -> None:
        super().__init__()
        self.setWindowTitle("PC Controller - Dashboard (Phase 3)")
        self._network = network
        self._logger = logging.getLogger("pc_controller.gui")
        self._ensure_data_dir()

        # Central tabs
        self.tabs = QTabWidget(self)
        self.setCentralWidget(self.tabs)

        # Dashboard tab with dynamic grid
        self.dashboard = QWidget(self)
        self.grid = QGridLayout(self.dashboard)
        self.grid.setContentsMargins(8, 8, 8, 8)
        self.grid.setSpacing(8)
        self.tabs.addTab(self.dashboard, "Dashboard")

        # Logs tab
        self.logs = QTextEdit(self)
        self.logs.setReadOnly(True)
        self.tabs.addTab(self.logs, "Logs")

        # Toolbar
        self._setup_toolbar()

        # Local device widgets and interfaces
        self.webcam_widget = DeviceWidget("video", "Local Webcam", self)
        self.gsr_widget = DeviceWidget("gsr", "Shimmer GSR (Local)", self)
        self._add_to_grid(self.webcam_widget)
        self._add_to_grid(self.gsr_widget)

        # Interfaces (optional shims)
        self.webcam = WebcamInterface() if WebcamInterface else None
        self.shimmer = ShimmerInterface() if ShimmerInterface else None

        # Timers to poll devices without blocking UI
        self.video_timer = QTimer(self)
        self.video_timer.setInterval(33)  # ~30 FPS
        self.video_timer.timeout.connect(self._on_video_timer)

        self.gsr_timer = QTimer(self)
        self.gsr_timer.setInterval(50)  # 20 Hz UI updates, data @128 Hz aggregated
        self.gsr_timer.timeout.connect(self._on_gsr_timer)

        # Wire network logs
        self._network.device_discovered.connect(self._on_device_discovered)
        self._network.device_removed.connect(self._on_device_removed)
        self._network.log.connect(self._on_log)
        self.ui_log.connect(self._on_log)

        # Start discovery and local streaming by default
        self._network.start()
        try:
            if self.webcam:
                self.webcam.start()
            if self.shimmer:
                self.shimmer.start()
        except Exception as exc:  # noqa: BLE001
            self._log(f"Local device start error: {exc}")

        self.video_timer.start()
        self.gsr_timer.start()

        # Recording state
        self._recording = False
        self._video_writer = None
        self._gsr_file = None
        self._gsr_written_header = False

    # Toolbar setup
    def _setup_toolbar(self) -> None:
        toolbar = QToolBar("Session Controls", self)
        self.addToolBar(toolbar)

        self.act_start = QAction("Start Session", self)
        self.act_stop = QAction("Stop Session", self)
        self.act_connect = QAction("Connect Device", self)

        self.act_start.triggered.connect(self._on_start_session)
        self.act_stop.triggered.connect(self._on_stop_session)
        self.act_connect.triggered.connect(self._on_connect_device)

        toolbar.addAction(self.act_start)
        toolbar.addAction(self.act_stop)
        toolbar.addSeparator()
        toolbar.addAction(self.act_connect)

    # Grid management: place next available cell in 2-column layout
    def _add_to_grid(self, widget: QWidget) -> None:
        count = self.grid.count()
        col_count = 2
        row = count // col_count
        col = count % col_count
        self.grid.addWidget(widget, row, col)

    # Handlers
    def _on_start_session(self) -> None:
        if self._recording:
            return
        ts = time.strftime("%Y%m%d_%H%M%S")
        self._session_dir = os.path.join(os.getcwd(), "pc_controller_data", ts)
        os.makedirs(self._session_dir, exist_ok=True)
        self._open_recorders(self._session_dir)
        self._recording = True
        self._log(f"Session started: {self._session_dir}")

    def _on_stop_session(self) -> None:
        if not self._recording:
            return
        self._close_recorders()
        self._recording = False
        self._log("Session stopped.")

    def _on_connect_device(self) -> None:
        self._log("Connect Device action: use the Network tab in a future phase.")

    @pyqtSlot(DiscoveredDevice)
    def _on_device_discovered(self, device: DiscoveredDevice) -> None:
        self._log(f"Discovered: {device.name} @ {device.address}:{device.port}")

    @pyqtSlot(str)
    def _on_device_removed(self, name: str) -> None:
        self._log(f"Removed: {name}")

    @pyqtSlot(str)
    def _on_log(self, message: str) -> None:
        self.logs.append(message)
        self._logger.info(message)

    def _log(self, message: str) -> None:
        self.ui_log.emit(message)

    # Timers
    def _on_video_timer(self) -> None:
        try:
            if not self.webcam:
                return
            frame = self.webcam.get_latest_frame()
            if frame is not None:
                self.webcam_widget.update_video_frame(frame)
                if self._recording:
                    self._write_video_frame(frame)
        except Exception as exc:  # noqa: BLE001
            self._log(f"Video update error: {exc}")

    def _on_gsr_timer(self) -> None:
        try:
            if not self.shimmer:
                return
            ts, vals = self.shimmer.get_latest_samples()
            if ts.size:
                self.gsr_widget.append_gsr_samples(ts, vals)
                if self._recording:
                    self._write_gsr_samples(ts, vals)
        except Exception as exc:  # noqa: BLE001
            self._log(f"GSR update error: {exc}")

    # Recording helpers
    def _ensure_data_dir(self) -> None:
        base = os.path.join(os.getcwd(), "pc_controller_data")
        os.makedirs(base, exist_ok=True)

    def _open_recorders(self, session_dir: str) -> None:
        # Open GSR CSV
        self._gsr_path = os.path.join(session_dir, "gsr.csv")
        self._gsr_file = open(self._gsr_path, "w", encoding="utf-8")
        self._gsr_file.write("timestamp_ns,gsr_microsiemens\n")
        self._gsr_written_header = True
        # Open video writer if OpenCV available
        try:
            import cv2  # local import

            self._video_path = os.path.join(session_dir, "webcam.avi")
            self._video_fps = 30.0
            self._video_writer = cv2.VideoWriter(
                self._video_path,
                cv2.VideoWriter_fourcc(*"MJPG"),
                self._video_fps,
                (640, 480),
            )
            if not self._video_writer.isOpened():
                self._log("Failed to open VideoWriter; will skip video recording.")
                self._video_writer = None
        except Exception:
            self._video_writer = None
            self._log("OpenCV not available; video recording disabled.")

    def _close_recorders(self) -> None:
        try:
            if self._gsr_file:
                self._gsr_file.close()
        finally:
            self._gsr_file = None
        if self._video_writer is not None:
            try:
                self._video_writer.release()
            except Exception:
                pass
            self._video_writer = None

    def _write_gsr_samples(self, ts: np.ndarray, vals: np.ndarray) -> None:
        if not self._gsr_file:
            return
        for t, v in zip(ts, vals):
            self._gsr_file.write(f"{int(t*1e9)},{v:.6f}\n")
        self._gsr_file.flush()

    def _write_video_frame(self, frame_bgr: np.ndarray) -> None:
        if self._video_writer is None:
            return
        try:
            import cv2  # local import

            # Ensure frame is 640x480 BGR
            fb = frame_bgr
            if fb is None:
                return
            h, w = fb.shape[:2]
            if (w, h) != (640, 480):
                fb = cv2.resize(fb, (640, 480))
            self._video_writer.write(fb)
        except Exception as exc:  # noqa: BLE001
            self._log(f"Video write error: {exc}")
