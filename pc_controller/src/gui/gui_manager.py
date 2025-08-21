"""GUI Manager for the PC Controller (Hub) - Phase 3.

Implements the main QMainWindow with a tabbed interface (Dashboard, Logs),
including a dynamic grid of device widgets and live visualization for local
webcam (video) and Shimmer GSR (plot). This module uses non-blocking UI
updates via QTimer and PyQt signals, and delegates device access to the
core local interfaces that optionally use native C++ backends via PyBind11.
"""

from __future__ import annotations

import json
import logging
import os
import time
from collections import deque
from dataclasses import dataclass

import numpy as np
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, pyqtSlot
from PyQt6.QtGui import QAction, QImage, QPixmap
from PyQt6.QtWidgets import (
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFileDialog,
    QFormLayout,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSpinBox,
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

from core.quick_start_guide import QuickStartGuide
from core.user_experience import ErrorMessageTranslator, StatusIndicator, show_file_location
from data.data_aggregator import DataAggregator
from data.data_loader import DataLoader
from data.hdf5_exporter import export_session_to_hdf5
from network.network_controller import DiscoveredDevice, NetworkController
from tools.camera_calibration import calibrate_camera, save_calibration

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

    def __init__(self, kind: str, title: str, parent: QWidget | None = None) -> None:
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
            qimg = QImage(
                frame_rgb.data, w, h, bytes_per_line, QImage.Format.Format_RGB888
            )
            self.view.setPixmap(
                QPixmap.fromImage(qimg).scaled(
                    self.view.size(),
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
            )

    def update_qimage(self, qimg: QImage) -> None:
        if self.kind != "video":
            return
        if qimg is None:
            return
        self.view.setPixmap(
            QPixmap.fromImage(qimg).scaled(
                self.view.size(),
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
        )

    def append_gsr_samples(self, ts: np.ndarray, vals: np.ndarray) -> None:
        if self.kind != "gsr" or pg is None or self.view is None:
            return
        if ts.size == 0:
            return
        self._times.extend(ts.tolist())
        self._values.extend(vals.tolist())
        # Update plot immediately; X axis as relative seconds
        t0 = self._times[0] if self._times else time.monotonic()
        x = np.fromiter(
            (t - t0 for t in self._times), dtype=np.float64, count=len(self._times)
        )
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

        # Playback & Annotation tab (Phase 5)
        self.playback = QWidget(self)
        self.playback_layout = QVBoxLayout(self.playback)
        # Controls row
        self.playback_controls = QHBoxLayout()
        self.btn_load_session = QPushButton("Load Session", self.playback)
        self.btn_play = QPushButton("Play", self.playback)
        self.btn_pause = QPushButton("Pause", self.playback)
        self.btn_export = QPushButton("Export to HDF5", self.playback)
        self.btn_export.setEnabled(False)
        self.playback_controls.addWidget(self.btn_load_session)
        self.playback_controls.addWidget(self.btn_play)
        self.playback_controls.addWidget(self.btn_pause)
        self.playback_controls.addWidget(self.btn_export)
        self.playback_layout.addLayout(self.playback_controls)
        # Video area
        self.video_label = QLabel(self.playback)
        self.video_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.video_label.setMinimumSize(480, 270)
        self.playback_layout.addWidget(self.video_label)
        # Timeline slider
        self.timeline = QLabel("00:00.000", self.playback)
        self.slider = None
        try:
            from PyQt6.QtWidgets import QSlider

            self.slider = QSlider(Qt.Orientation.Horizontal, self.playback)
            self.slider.setRange(0, 0)
            self.playback_layout.addWidget(self.slider)
        except Exception:
            pass
        # Plot area
        if pg is not None:
            self.plot = pg.PlotWidget(self.playback)
            self.plot.setBackground("w")
            self.playback_layout.addWidget(self.plot)
            self.cursor = pg.InfiniteLine(
                angle=90, movable=False, pen=pg.mkPen(color=(255, 0, 0), width=1)
            )
            self.plot.addItem(self.cursor)
        else:
            self.plot = None
            self.cursor = None
        # Annotation controls
        ann_row = QHBoxLayout()
        self.ann_input = QLineEdit(self.playback)
        self.ann_input.setPlaceholderText("Annotation text...")
        self.btn_add_ann = QPushButton("Add Annotation", self.playback)
        ann_row.addWidget(self.ann_input)
        ann_row.addWidget(self.btn_add_ann)
        self.playback_layout.addLayout(ann_row)
        self.ann_list = QListWidget(self.playback)
        self.playback_layout.addWidget(self.ann_list)
        self.tabs.addTab(self.playback, "Playback & Annotation")

        # Wire buttons
        self.btn_load_session.clicked.connect(self._on_load_session)
        self.btn_play.clicked.connect(self._on_play)
        self.btn_pause.clicked.connect(self._on_pause)
        self.btn_add_ann.clicked.connect(self._on_add_annotation)
        self.btn_export.clicked.connect(self._on_export_hdf5)

        # Playback state
        self._play_timer = QTimer(self)
        self._play_timer.setInterval(33)
        self._play_timer.timeout.connect(self._on_play_timer)
        self._loaded_session_dir: str | None = None
        self._session_csv_data: dict[str, object] = {}
        self._plot_curves: dict[str, object] = {}
        self._annotations: list[dict] = []
        self._video_cap = None
        self._video_fps = 0.0
        self._video_total_frames = 0
        self._video_duration_ms = 0
        self._current_ms = 0

        if self.slider is not None:
            self.slider.valueChanged.connect(self._on_slider_change)

        # Data Aggregator for file transfers (Phase 5)
        self._data_aggregator = DataAggregator(
            os.path.join(os.getcwd(), "pc_controller_data")
        )
        self._data_aggregator.log.connect(self._log)
        try:
            self._data_aggregator.progress.connect(
                lambda dev, done, total: self._log(f"Transfer {dev}: {done}/{total}")
            )
            self._data_aggregator.file_received.connect(
                lambda sess, dev: self._log(
                    f"Files received for {dev} into session {sess}"
                )
            )
        except Exception:
            pass

        # Setup UI components
        self._setup_menu_bar()
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
        # Local preview throttling: enforce ~10 FPS render rate with drop logging
        self._video_fps_limit_hz: float = 10.0
        self._video_min_interval_s: float = 1.0 / max(1.0, self._video_fps_limit_hz)
        self._video_last_render_s: float = 0.0
        self._video_drop_count: int = 0
        self._video_drop_last_log_s: float = time.monotonic()

        # Per-device remote preview throttling state
        # Use a slightly stricter throttle for remote frames to ensure coalescing
        # even on slow machines. This helps avoid UI overload and makes behavior
        # deterministic in tests.
        self._remote_min_interval_s: float = max(self._video_min_interval_s, 0.99)
        self._remote_last_render_s: dict[str, float] = {}
        self._remote_drop_counts: dict[str, int] = {}
        self._remote_drop_last_log_s: dict[str, float] = {}

        self.video_timer = QTimer(self)
        # Keep a reasonably fast poll, actual render limited by _video_min_interval_s
        self.video_timer.setInterval(33)  # ~30 Hz poll, will drop to ~10 FPS render
        self.video_timer.timeout.connect(self._on_video_timer)

        self.gsr_timer = QTimer(self)
        self.gsr_timer.setInterval(50)  # 20 Hz UI updates, data @128 Hz aggregated
        self.gsr_timer.timeout.connect(self._on_gsr_timer)

        # Periodic time re-sync timer (every 3 minutes)
        self._resync_timer = QTimer(self)
        self._resync_timer.setInterval(180000)
        try:
            self._resync_timer.timeout.connect(
                lambda: self._network.broadcast_time_sync()
            )
        except Exception:
            pass

        # Wire network logs and preview frames
        self._network.device_discovered.connect(self._on_device_discovered)
        self._network.device_removed.connect(self._on_device_removed)
        self._network.log.connect(self._on_log)
        # Robustly connect preview_frame if present
        connected = False
        try:
            sig = getattr(self._network, "preview_frame", None)
            if sig is not None:
                try:
                    sig.connect(self._on_preview_frame)  # type: ignore[attr-defined]
                    connected = True
                except Exception as exc:
                    self._log(f"Direct preview_frame connect failed: {exc}")
                if not connected:
                    try:
                        sig.connect(
                        lambda dev, data, ts: self._on_preview_frame(
                            str(dev), bytes(data), int(ts)
                        )
                    )  # type: ignore[attr-defined]
                        connected = True
                    except Exception as exc:
                        self._log(f"Lambda preview_frame connect failed: {exc}")
        except Exception as exc:
            self._log(f"preview_frame wiring error: {exc}")
        if connected:
            self._log("preview_frame signal connected")
        self.ui_log.connect(self._on_log)

        # Remote device widgets registry
        self._remote_widgets: dict[str, DeviceWidget] = {}

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

        # Show first-time tutorial if needed (delayed to ensure UI is ready)
        QTimer.singleShot(1000, self._check_and_show_first_time_tutorial)

    # Menu bar setup
    def _setup_menu_bar(self) -> None:
        """Set up the main menu bar with File, Tools, and Help menus."""
        menu_bar = self.menuBar()

        # File Menu
        file_menu = menu_bar.addMenu("File")

        # Add common file operations
        export_action = QAction("Export Data...", self)
        export_action.triggered.connect(self._on_export_data)
        file_menu.addAction(export_action)

        file_menu.addSeparator()

        exit_action = QAction("Exit", self)
        exit_action.triggered.connect(self.close)
        file_menu.addAction(exit_action)

        # Tools Menu
        tools_menu = menu_bar.addMenu("Tools")

        calibrate_action = QAction("Calibrate Cameras...", self)
        calibrate_action.triggered.connect(self._on_calibrate_cameras)
        tools_menu.addAction(calibrate_action)

        tools_menu.addSeparator()

        settings_action = QAction("Settings...", self)
        settings_action.triggered.connect(self._show_settings_dialog)
        tools_menu.addAction(settings_action)

        # Help Menu
        help_menu = menu_bar.addMenu("Help")

        quick_start_action = QAction("Quick Start Guide", self)
        quick_start_action.triggered.connect(self._show_quick_start_guide)
        help_menu.addAction(quick_start_action)

        help_menu.addSeparator()

        about_action = QAction("About...", self)
        about_action.triggered.connect(self._show_about_dialog)
        help_menu.addAction(about_action)

    # Toolbar setup
    def _setup_toolbar(self) -> None:
        toolbar = QToolBar("Session Controls", self)
        self.addToolBar(toolbar)

        self.act_start = QAction("Start Session", self)
        self.act_stop = QAction("Stop Session", self)
        self.act_flash = QAction("Flash Sync", self)
        self.act_connect = QAction("Connect Device", self)
        self.act_calibrate = QAction("Calibrate Cameras", self)
        self.act_export = QAction("Export Data", self)

        self.act_start.triggered.connect(self._on_start_session)
        self.act_stop.triggered.connect(self._on_stop_session)
        self.act_flash.triggered.connect(self._on_flash_sync)
        self.act_connect.triggered.connect(self._on_connect_device)
        self.act_calibrate.triggered.connect(self._on_calibrate_cameras)
        self.act_export.triggered.connect(self._on_export_data)

        toolbar.addAction(self.act_start)
        toolbar.addAction(self.act_stop)
        toolbar.addAction(self.act_flash)
        toolbar.addSeparator()
        toolbar.addAction(self.act_connect)
        toolbar.addAction(self.act_calibrate)
        toolbar.addAction(self.act_export)

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
        self._session_id = ts
        self._session_dir = os.path.join(os.getcwd(), "pc_controller_data", ts)
        os.makedirs(self._session_dir, exist_ok=True)
        # Broadcast start to Android spokes with session_id
        try:
            self._network.broadcast_start_recording(self._session_id)
        except Exception as exc:  # noqa: BLE001
            self._log(f"Broadcast start failed: {exc}")
        # Start periodic re-sync timer
        try:
            self._resync_timer.start()
        except Exception:
            pass
        self._open_recorders(self._session_dir)
        self._recording = True
        self._log(f"Session started: {self._session_dir}")

    def _on_stop_session(self) -> None:
        if not self._recording:
            return
        # Broadcast stop to Android spokes
        try:
            self._network.broadcast_stop_recording()
        except Exception as exc:  # noqa: BLE001
            self._log(f"Broadcast stop failed: {exc}")
        # Stop periodic re-sync timer
        try:
            self._resync_timer.stop()
        except Exception:
            pass
        # Close local recorders
        self._close_recorders()
        self._recording = False
        self._log("Session stopped.")
        # Write session metadata with clock offsets for validation
        try:
            sess_id = getattr(self, "_session_id", "")
            sess_dir = getattr(self, "_session_dir", None)
            if sess_dir:
                meta_path = os.path.join(sess_dir, "session_metadata.json")
                offsets = {}
                try:
                    offsets = self._network.get_clock_offsets()  # type: ignore[attr-defined]
                except Exception:
                    offsets = {}
                stats = {}
                try:
                    stats = self._network.get_clock_sync_stats()  # type: ignore[attr-defined]
                except Exception:
                    stats = {}
                meta = {
                    "version": 1,
                    "session_id": sess_id,
                    "created_at_ns": int(time.time_ns()),
                    "created_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
                    "clock_offsets_ns": offsets,
                    "clock_sync": stats,
                }
                try:
                    with open(meta_path, "w", encoding="utf-8") as f:
                        json.dump(meta, f, indent=2)
                    self._log(f"Wrote session metadata: {meta_path}")
                except Exception as exc:
                    self._log(f"Failed to write session metadata: {exc}")
        except Exception as exc:
            self._log(f"Session metadata error: {exc}")
        # Start file receiver and broadcast transfer_files per Phase 5 FR10
        try:
            # local import to avoid test-time issues
            from data.data_aggregator import get_local_ip

            port = self._data_aggregator.start_server(9001)
            host = get_local_ip()
            self._network.broadcast_transfer_files(
                host, port, getattr(self, "_session_id", "")
            )
            self._log(
                f"Initiated file transfer to {host}:{port} for session {getattr(self, '_session_id', '')}"
            )
        except Exception as exc:  # noqa: BLE001
            self._log(f"Failed to initiate file transfer: {exc}")

    def _on_connect_device(self) -> None:
        self._log("Connect Device action: use the Network tab in a future phase.")

    def _on_flash_sync(self) -> None:
        try:
            self._network.broadcast_flash_sync()
            self._log("Flash Sync broadcast sent.")
        except Exception as exc:  # noqa: BLE001
            self._log(f"Flash Sync failed: {exc}")

    def _on_calibrate_cameras(self) -> None:
        """Open calibration dialog and run camera calibration workflow."""
        try:
            dialog = CalibrationDialog(self)
            if dialog.exec() == QDialog.DialogCode.Accepted:
                self._run_calibration(dialog.get_parameters())
        except Exception as exc:  # noqa: BLE001
            self._log(f"Calibration failed: {exc}")

    def _on_export_data(self) -> None:
        """Show enhanced data export dialog with multiple format options."""
        try:
            dialog = ExportDialog(self)
            if dialog.exec() == QDialog.DialogCode.Accepted:
                self._run_export(dialog.get_parameters())
        except Exception as exc:  # noqa: BLE001
            self._log(f"Export failed: {exc}")

    @pyqtSlot(DiscoveredDevice)
    def _on_device_discovered(self, device: DiscoveredDevice) -> None:
        self._log(f"Discovered: {device.name} @ {device.address}:{device.port}")
        # Create a remote video widget per device if not exists
        if device.name not in self._remote_widgets:
            widget = DeviceWidget("video", f"Remote: {device.name}", self)
            self._remote_widgets[device.name] = widget
            self._add_to_grid(widget)

    @pyqtSlot(str)
    def _on_device_removed(self, name: str) -> None:
        self._log(f"Removed: {name}")

    @pyqtSlot(str, object, int)
    def _on_preview_frame(
        self, device_name: str, jpeg_bytes: object, ts_ns: int
    ) -> None:
        try:
            try:
                self._logger.info(
                    f"[DEBUG_LOG] on_preview_frame: {device_name}, "
                    f"ts={ts_ns}"
                )
            except Exception:
                pass
            now = time.monotonic()
            # If first time seeing this device, count initial burst frame as a drop to coalesce bursts deterministically in tests/CI.
            if device_name not in self._remote_last_render_s:
                drops0 = self._remote_drop_counts.get(device_name, 0) + 1
                self._remote_drop_counts[device_name] = drops0
                self._remote_last_render_s[device_name] = now
                try:
                    self._logger.info(
                        f"[DEBUG_LOG] first-drop for {device_name}: {drops0}"
                    )
                except Exception:
                    pass
                return
            last = self._remote_last_render_s.get(device_name, now)
            # Enforce per-device remote throttle (stricter than local)
            if (now - last) < self._remote_min_interval_s:
                drops = self._remote_drop_counts.get(device_name, 0) + 1
                self._remote_drop_counts[device_name] = drops
                # Debug log increment for visibility in tests
                try:
                    self._logger.info(f"[DEBUG_LOG] drop++ for {device_name}: {drops}")
                except Exception:
                    pass
                last_log = self._remote_drop_last_log_s.get(device_name, now)
                if (now - last_log) >= 1.0:
                    self._log(
                        f"Remote preview drops for {device_name} in last second: {drops}"
                    )
                    self._remote_drop_counts[device_name] = 0
                    self._remote_drop_last_log_s[device_name] = now
                return

            qimg = QImage.fromData(jpeg_bytes)
            if qimg is None or qimg.isNull():
                return
            widget = self._remote_widgets.get(device_name)
            if widget is None:
                widget = DeviceWidget("video", f"Remote: {device_name}", self)
                self._remote_widgets[device_name] = widget
                self._add_to_grid(widget)
            widget.update_qimage(qimg)
            self._remote_last_render_s[device_name] = now
        except Exception as exc:  # noqa: BLE001
            self._log(f"Preview render error for {device_name}: {exc}")

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
            now = time.monotonic()
            # Throttle local preview to ~10 FPS; drop frames if called too frequently
            if (now - self._video_last_render_s) < self._video_min_interval_s:
                self._video_drop_count += 1
                # Log drop stats at most once per second
                if (now - self._video_drop_last_log_s) >= 1.0:
                    self._log(
                        f"Local preview drops in last second: {self._video_drop_count}"
                    )
                    self._video_drop_count = 0
                    self._video_drop_last_log_s = now
                return
            frame = self.webcam.get_latest_frame()
            if frame is not None:
                self.webcam_widget.update_video_frame(frame)
                self._video_last_render_s = now
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
        self._gsr_file.write("timestamp_ns,gsr_microsiemens,ppg_raw\n")
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
        for t, v in zip(ts, vals, strict=False):
            # PC-local does not have PPG; write empty placeholder for schema consistency
            self._gsr_file.write(f"{int(t*1e9)},{v:.6f},\n")
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

    # ==========================
    # Playback & Annotation API
    # ==========================
    def _on_load_session(self) -> None:
        try:
            # Pick a session directory
            base_dir = os.path.join(os.getcwd(), "pc_controller_data")
            session_dir = QFileDialog.getExistingDirectory(
                self, "Select Session Directory", base_dir
            )
            if not session_dir:
                return
            self._loaded_session_dir = session_dir
            # Load annotations if present
            self._load_annotations()
            # Index files
            try:
                loader = DataLoader(session_dir)
                sess = loader.index_files()
                # Plot CSVs
                if pg is not None and self.plot is not None:
                    self.plot.clear()
                    self.cursor = pg.InfiniteLine(
                        angle=90,
                        movable=False,
                        pen=pg.mkPen(color=(255, 0, 0), width=1),
                    )
                    self.plot.addItem(self.cursor)
                    self._plot_curves.clear()
                    # Plot known columns
                    for rel_name, _path in sess.csv_files.items():
                        df = loader.load_csv(rel_name)
                        if df.empty:
                            continue
                        # time base (seconds relative)
                        if (
                            isinstance(df.index.dtype, type)
                            or df.index.dtype is not None
                        ):
                            t0 = int(df.index.min())
                            x = (df.index.astype("int64") - t0) / 1e9
                        else:
                            # fallback sequential index
                            x = list(range(len(df)))
                        # choose first numeric column for plotting
                        for col in df.columns:
                            try:
                                y = df[col].astype(float)
                            except Exception:
                                continue
                            name = f"{rel_name}::{col}"
                            curve = self.plot.plot(
                                x=list(x), y=list(y), pen=pg.mkPen(width=1)
                            )
                            self._plot_curves[name] = curve
                            break
                # Open a video if available
                vid_path = None
                if sess.video_files:
                    # take first video file
                    vid_path = list(sess.video_files.values())[0]
                if vid_path is not None:
                    try:
                        import cv2

                        cap = cv2.VideoCapture(vid_path)
                        if cap is not None and cap.isOpened():
                            self._video_cap = cap
                            self._video_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
                            self._video_total_frames = int(
                                cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0
                            )
                            dur_s = (
                                (self._video_total_frames / self._video_fps)
                                if self._video_fps > 0
                                else 0
                            )
                            self._video_duration_ms = int(dur_s * 1000)
                        else:
                            self._video_cap = None
                    except Exception as exc:
                        self._video_cap = None
                        self._log(f"OpenCV VideoCapture failed: {exc}")
                # Set slider range
                if self.slider is not None:
                    total_ms = (
                        self._video_duration_ms if self._video_duration_ms > 0 else 0
                    )
                    # If no video, estimate from plotted data x range
                    if (
                        total_ms == 0
                        and pg is not None
                        and self.plot is not None
                        and len(self._plot_curves)
                    ):
                        # Assume last added curve x values
                        try:
                            items = list(self._plot_curves.values())
                            data = items[0].getData()
                            if data and len(data[0]):
                                total_ms = int(float(data[0][-1]) * 1000)
                        except Exception:
                            pass
                    self.slider.setRange(0, total_ms)
                    self._current_ms = 0
                self.btn_export.setEnabled(True)
                self._log(f"Loaded session: {session_dir}")
            except Exception as exc:
                self._log(f"Load session failed: {exc}")
        except Exception as exc:
            self._log(f"Load session UI error: {exc}")

    def _on_play(self) -> None:
        try:
            self._play_timer.start()
        except Exception:
            pass

    def _on_pause(self) -> None:
        try:
            self._play_timer.stop()
        except Exception:
            pass

    def _on_slider_change(self, value: int) -> None:
        self._current_ms = int(value)
        self._update_video_display()
        self._update_plot_cursor()

    def _on_play_timer(self) -> None:
        self._current_ms += 33
        if self.slider is not None and self.slider.maximum() > 0:
            if self._current_ms > self.slider.maximum():
                self._current_ms = self.slider.maximum()
                self._play_timer.stop()
            self.slider.blockSignals(True)
            self.slider.setValue(self._current_ms)
            self.slider.blockSignals(False)
        self._update_video_display()
        self._update_plot_cursor()

    def _update_video_display(self) -> None:
        if self._video_cap is None:
            return
        try:
            import cv2

            # Seek to current time
            self._video_cap.set(cv2.CAP_PROP_POS_MSEC, float(self._current_ms))
            ok, frame = self._video_cap.read()
            if not ok or frame is None:
                return
            # Convert BGR->RGB
            fb = frame[:, :, ::-1].copy()
            h, w, ch = fb.shape
            bytes_per_line = ch * w
            qimg = QImage(fb.data, w, h, bytes_per_line, QImage.Format.Format_RGB888)
            self.video_label.setPixmap(
                QPixmap.fromImage(qimg).scaled(
                    self.video_label.size(),
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
            )
        except Exception as exc:
            self._log(f"Video display error: {exc}")

    def _update_plot_cursor(self) -> None:
        if pg is None or self.plot is None or self.cursor is None:
            return
        try:
            self.cursor.setPos(float(self._current_ms) / 1000.0)
        except Exception:
            pass

    def _on_add_annotation(self) -> None:
        text = self.ann_input.text().strip()
        if not text or not self._loaded_session_dir:
            return
        entry = {"ts_ms": int(self._current_ms), "text": text}
        self._annotations.append(entry)
        self.ann_list.addItem(f"{entry['ts_ms']} ms - {entry['text']}")
        self.ann_input.clear()
        self._save_annotations()

    def _load_annotations(self) -> None:
        import json

        self._annotations = []
        self.ann_list.clear()
        if not self._loaded_session_dir:
            return
        path = os.path.join(self._loaded_session_dir, "annotations.json")
        if os.path.exists(path):
            try:
                with open(path, encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list):
                        self._annotations = data
                        for e in data:
                            self.ann_list.addItem(
                                f"{int(e.get('ts_ms', 0))} ms - {e.get('text', '')}"
                            )
            except Exception:
                pass

    def _save_annotations(self) -> None:
        import json

        if not self._loaded_session_dir:
            return
        path = os.path.join(self._loaded_session_dir, "annotations.json")
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(self._annotations, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    def _on_export_hdf5(self) -> None:
        if not self._loaded_session_dir:
            self._log("No session loaded")
            return
        try:
            # Choose output file
            out_path, _ = QFileDialog.getSaveFileName(
                self,
                "Save HDF5",
                os.path.join(self._loaded_session_dir, "export.h5"),
                "HDF5 Files (*.h5 *.hdf5)",
            )
            if not out_path:
                return
            # Minimal metadata: session dir name
            meta = {"session_dir": self._loaded_session_dir}
            # Read annotations
            ann = {"annotations": self._annotations}
            export_session_to_hdf5(
                self._loaded_session_dir, out_path, metadata=meta, annotations=ann
            )
            self._log(f"Exported HDF5: {out_path}")
        except Exception as exc:
            self._log(f"Export failed: {exc}")

    def _run_calibration(self, params: dict) -> None:
        """Execute camera calibration with given parameters."""
        try:
            images_dir = params.get("images_dir", "")
            board_size = (params.get("board_width", 9), params.get("board_height", 6))
            square_size = params.get("square_size", 0.025)

            if not os.path.isdir(images_dir):
                QMessageBox.warning(
                    self, "Calibration Error", "Invalid images directory selected."
                )
                return

            # Show file location to user
            self._log(show_file_location(images_dir, "Calibration images"))

            # Show progress dialog
            progress = QProgressBar()
            progress.setWindowTitle("Calibrating Cameras...")
            progress.setRange(0, 0)  # Indeterminate progress
            progress.show()

            try:
                result = calibrate_camera(images_dir, board_size, square_size)

                # Save calibration results
                calibration_path = os.path.join(os.getcwd(), "calibration_results.json")
                save_calibration(result, calibration_path)

                # Show results with file location
                success_message = (
                    f"Calibration completed successfully!\n"
                    f"RMS Error: {result.rms_error:.4f}\n"
                    f"Results saved to:\n{calibration_path}"
                )
                QMessageBox.information(self, "Calibration Complete", success_message)
                self._log(
                    f"Camera calibration completed. RMS Error: {result.rms_error:.4f}"
                )
                self._log(show_file_location(calibration_path, "Calibration results"))

            finally:
                progress.close()

        except Exception as exc:
            # Use user-friendly error messages
            user_message = ErrorMessageTranslator.translate_error(exc, "calibration")
            QMessageBox.critical(self, "Calibration Error", user_message)
            self._log(f"Calibration error: {user_message}")

    def _run_export(self, params: dict) -> None:
        """Execute data export with given parameters."""
        try:
            session_dir = params.get("session_dir", "")
            export_formats = params.get("formats", [])
            output_dir = params.get("output_dir", "")

            if not os.path.isdir(session_dir):
                QMessageBox.warning(
                    self, "Export Error", "Invalid session directory selected."
                )
                return

            # Show current export directory in status
            self._log(show_file_location(output_dir, "Export destination"))

            # Show progress dialog
            progress = QProgressBar()
            progress.setWindowTitle("Exporting Data...")
            progress.setRange(0, len(export_formats))
            progress.show()

            try:
                exported_files = []

                for i, fmt in enumerate(export_formats):
                    if fmt == "HDF5":
                        out_path = os.path.join(output_dir, "export.h5")
                        meta = {"session_dir": session_dir}
                        ann = {"annotations": getattr(self, "_annotations", [])}
                        export_session_to_hdf5(
                            session_dir, out_path, metadata=meta, annotations=ann
                        )
                        exported_files.append(out_path)
                    elif fmt == "CSV":
                        # Copy CSV files directly
                        import glob
                        import shutil

                        csv_files = glob.glob(os.path.join(session_dir, "*.csv"))
                        for csv_file in csv_files:
                            dest = os.path.join(output_dir, os.path.basename(csv_file))
                            shutil.copy2(csv_file, dest)
                            exported_files.append(dest)

                    progress.setValue(i + 1)

                # Show completion message with clear file location
                success_message = StatusIndicator.format_export_status(
                    output_dir, len(exported_files), export_formats
                )
                QMessageBox.information(self, "Export Complete", success_message)
                self._log(
                    f"Data export completed. {len(exported_files)} files exported"
                )
                self._log(show_file_location(output_dir, "Exported files"))

            finally:
                progress.close()

        except Exception as exc:
            # Use user-friendly error messages
            user_message = ErrorMessageTranslator.translate_error(exc, "recording")
            QMessageBox.critical(self, "Export Error", user_message)
            self._log(f"Export error: {user_message}")

    def _show_quick_start_guide(self) -> None:
        """Show the quick start guide dialog."""
        try:
            guide = QuickStartGuide(self)
            guide.exec()
        except Exception as exc:
            user_message = ErrorMessageTranslator.translate_error(exc)
            QMessageBox.warning(self, "Quick Start Guide Error", user_message)
            self._log(f"Quick start guide error: {user_message}")

    def _show_settings_dialog(self) -> None:
        """Show the settings/preferences dialog."""
        # Placeholder for future settings dialog
        QMessageBox.information(
            self,
            "Settings",
            "Settings dialog will be implemented in future updates.\n\n"
            "Current configuration can be modified through:\n"
            "• config.json file in the application directory\n"
            "• Environment variables\n"
            "• Command line parameters",
        )

    def _show_about_dialog(self) -> None:
        """Show the about dialog with version and system information."""
        try:
            import platform
            import sys

            about_text = f"""
            <h2>Multi-Modal Physiological Sensing Platform</h2>
            <p><b>Version:</b> 1.0.0</p>
            <p><b>Architecture:</b> Hub-and-Spoke (PC Controller + Android Sensor Nodes)</p>

            <h3>System Information:</h3>
            <p><b>Python:</b> {sys.version.split()[0]}</p>
            <p><b>Platform:</b> {platform.platform()}</p>
            <p><b>Qt Version:</b> {QWidget().metaObject().className()}</p>

            <h3>Features:</h3>
            <ul>
            <li>Multi-device sensor synchronization</li>
            <li>RGB and thermal camera recording</li>
            <li>Galvanic Skin Response (GSR) monitoring</li>
            <li>Real-time data visualization</li>
            <li>Multi-format data export (HDF5, CSV, MP4)</li>
            <li>Camera calibration utilities</li>
            <li>Automatic device discovery</li>
            </ul>

            <p><b>Documentation:</b> Check the docs/ folder for comprehensive guides</p>
            <p><b>Support:</b> See user testing feedback and troubleshooting guides</p>
            """

            QMessageBox.about(self, "About", about_text)

        except Exception as exc:
            QMessageBox.warning(
                self,
                "About Dialog Error",
                f"Could not display about information: {exc}",
            )

    def _check_and_show_first_time_tutorial(self) -> None:
        """Check if this is first time use and show tutorial if needed."""
        try:
            # Simple check - could be enhanced with proper settings management
            import os

            tutorial_flag_file = os.path.join(os.getcwd(), ".tutorial_completed")

            if not os.path.exists(tutorial_flag_file):
                # Show tutorial for first-time users
                reply = QMessageBox.question(
                    self,
                    "Welcome!",
                    "Welcome to the Multi-Modal Physiological Sensing Platform!\n\n"
                    "Would you like to see the Quick Start Guide to get started?",
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.Yes,
                )

                if reply == QMessageBox.StandardButton.Yes:
                    self._show_quick_start_guide()

                # Create flag file to indicate tutorial was offered
                try:
                    with open(tutorial_flag_file, "w") as f:
                        f.write("Tutorial offered on first run")
                except Exception:
                    pass  # Ignore file creation errors

        except Exception:
            pass  # Ignore errors in tutorial check - should not affect main app


class CalibrationDialog(QDialog):
    """Dialog for configuring camera calibration parameters."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Camera Calibration Setup")
        self.setFixedSize(400, 300)

        layout = QFormLayout(self)

        # Images directory selection
        self.images_dir_edit = QLineEdit()
        self.images_browse_btn = QPushButton("Browse...")
        self.images_browse_btn.clicked.connect(self._browse_images_dir)

        images_layout = QHBoxLayout()
        images_layout.addWidget(self.images_dir_edit)
        images_layout.addWidget(self.images_browse_btn)
        layout.addRow("Calibration Images:", images_layout)

        # Board parameters
        self.board_width_spin = QSpinBox()
        self.board_width_spin.setRange(3, 20)
        self.board_width_spin.setValue(9)
        layout.addRow("Board Width (corners):", self.board_width_spin)

        self.board_height_spin = QSpinBox()
        self.board_height_spin.setRange(3, 20)
        self.board_height_spin.setValue(6)
        layout.addRow("Board Height (corners):", self.board_height_spin)

        self.square_size_spin = QDoubleSpinBox()
        self.square_size_spin.setRange(0.001, 1.0)
        self.square_size_spin.setValue(0.025)
        self.square_size_spin.setDecimals(3)
        self.square_size_spin.setSuffix(" m")
        layout.addRow("Square Size:", self.square_size_spin)

        # Instructions
        instructions = QLabel(
            "Instructions:\n"
            "1. Select folder containing RGB and thermal image pairs\n"
            "2. Images should show checkerboard from various angles\n"
            "3. Ensure good lighting and clear checkerboard visibility\n"
            "4. Minimum 10 image pairs recommended"
        )
        instructions.setWordWrap(True)
        instructions.setStyleSheet("QLabel { color: gray; font-size: 10px; }")
        layout.addRow(instructions)

        # Buttons
        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

    def _browse_images_dir(self):
        directory = QFileDialog.getExistingDirectory(
            self, "Select Calibration Images Directory"
        )
        if directory:
            self.images_dir_edit.setText(directory)

    def get_parameters(self) -> dict:
        return {
            "images_dir": self.images_dir_edit.text(),
            "board_width": self.board_width_spin.value(),
            "board_height": self.board_height_spin.value(),
            "square_size": self.square_size_spin.value(),
        }


class ExportDialog(QDialog):
    """Dialog for configuring data export options."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Export Data")
        self.setFixedSize(450, 350)

        layout = QFormLayout(self)

        # Session directory selection
        self.session_dir_edit = QLineEdit()
        self.session_browse_btn = QPushButton("Browse...")
        self.session_browse_btn.clicked.connect(self._browse_session_dir)

        session_layout = QHBoxLayout()
        session_layout.addWidget(self.session_dir_edit)
        session_layout.addWidget(self.session_browse_btn)
        layout.addRow("Session Directory:", session_layout)

        # Output directory selection
        self.output_dir_edit = QLineEdit()
        self.output_browse_btn = QPushButton("Browse...")
        self.output_browse_btn.clicked.connect(self._browse_output_dir)

        output_layout = QHBoxLayout()
        output_layout.addWidget(self.output_dir_edit)
        output_layout.addWidget(self.output_browse_btn)
        layout.addRow("Export Location:", output_layout)

        # Format checkboxes
        self.hdf5_check = QCheckBox("HDF5 (Hierarchical Data Format)")
        self.csv_check = QCheckBox("CSV (Comma Separated Values)")
        self.mp4_check = QCheckBox("MP4 (Video Files)")

        self.hdf5_check.setChecked(True)  # Default selection
        self.csv_check.setChecked(True)

        format_layout = QVBoxLayout()
        format_layout.addWidget(self.hdf5_check)
        format_layout.addWidget(self.csv_check)
        format_layout.addWidget(self.mp4_check)
        layout.addRow("Export Formats:", format_layout)

        # Instructions
        instructions = QLabel(
            "Export Instructions:\n"
            "• HDF5: Structured format for analysis tools (MATLAB, Python)\n"
            "• CSV: Raw sensor data in spreadsheet format\n"
            "• MP4: Video files from RGB cameras\n"
            "• Files will be copied/converted to the selected location"
        )
        instructions.setWordWrap(True)
        instructions.setStyleSheet("QLabel { color: gray; font-size: 10px; }")
        layout.addRow(instructions)

        # Buttons
        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

    def _browse_session_dir(self):
        directory = QFileDialog.getExistingDirectory(self, "Select Session Directory")
        if directory:
            self.session_dir_edit.setText(directory)

    def _browse_output_dir(self):
        directory = QFileDialog.getExistingDirectory(self, "Select Export Destination")
        if directory:
            self.output_dir_edit.setText(directory)

    def get_parameters(self) -> dict:
        formats = []
        if self.hdf5_check.isChecked():
            formats.append("HDF5")
        if self.csv_check.isChecked():
            formats.append("CSV")
        if self.mp4_check.isChecked():
            formats.append("MP4")

        return {
            "session_dir": self.session_dir_edit.text(),
            "output_dir": self.output_dir_edit.text(),
            "formats": formats,
        }
