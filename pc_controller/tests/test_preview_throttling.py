import os
import sys
import time
import base64
import pytest

# Skip if PyQt6 is not available
PyQt6 = pytest.importorskip("PyQt6")  # noqa: N816

from PyQt6.QtCore import QObject, pyqtSignal
from PyQt6.QtWidgets import QApplication

# Ensure pc_controller/src is on path
sys.path.append(os.path.join(os.getcwd(), "pc_controller", "src"))

# Force offscreen to avoid GUI requirements in CI
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

# Tiny 1x1 PNG (black) base64
_PNG_1x1_B64 = (
    b"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO9s6d8AAAAASUVORK5CYII="
)
_PNG_1x1 = base64.b64decode(_PNG_1x1_B64)


class _StubNetwork(QObject):
    device_discovered = pyqtSignal(object)
    device_removed = pyqtSignal(str)
    log = pyqtSignal(str)
    preview_frame = pyqtSignal(str, bytes, int)

    def __init__(self) -> None:
        super().__init__()
        self.started = False

    def start(self) -> None:
        self.started = True
        try:
            self.log.emit("Stub network started")
        except Exception:
            pass


@pytest.fixture(scope="module")
def qapp():
    app = QApplication.instance() or QApplication([])
    yield app


def test_remote_preview_throttling(qapp, monkeypatch):
    from gui import gui_manager as gm  # type: ignore

    # Disable local devices; we only test remote throttling here
    monkeypatch.setattr(gm, "WebcamInterface", None, raising=False)
    monkeypatch.setattr(gm, "ShimmerInterface", None, raising=False)

    net = _StubNetwork()
    ui = gm.GUIManager(network=net)
    try:
        device = "dev1"
        # Burst emit many preview frames rapidly to trigger throttling
        start = time.monotonic()
        for _ in range(200):
            net.preview_frame.emit(device, _PNG_1x1, 0)
            # Allow Qt to process events
            qapp.processEvents()
        elapsed = time.monotonic() - start
        # Ensure we ran quickly (<1s) so counters not reset by logging
        assert elapsed < 1.0
        drops = ui._remote_drop_counts.get(device, 0)  # type: ignore[attr-defined]
        # We expect some drops if throttling is active
        assert drops >= 1
    finally:
        ui.close()


def test_local_preview_throttling_logic(qapp, monkeypatch):
    import numpy as np
    from gui import gui_manager as gm  # type: ignore

    net = _StubNetwork()
    ui = gm.GUIManager(network=net)
    try:
        # Inject a fake webcam that always returns a frame instantly
        class _FakeCam:
            def get_latest_frame(self):
                return np.zeros((480, 640, 3), dtype=np.uint8)
        ui.webcam = _FakeCam()  # type: ignore

        # Call timer handler rapidly to trigger throttling
        for _ in range(50):
            ui._on_video_timer()  # type: ignore
        drops = ui._video_drop_count  # type: ignore[attr-defined]
        assert drops >= 1
    finally:
        ui.close()
