import os
import sys

import pytest

# Force offscreen mode before importing PyQt6 to avoid EGL issues
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

# Skip if PyQt6 is not available or if GUI libraries are missing
try:
    PyQt6 = pytest.importorskip("PyQt6")
    from PyQt6.QtCore import QObject, pyqtSignal
    from PyQt6.QtWidgets import QApplication
except ImportError as e:
    if "libEGL" in str(e) or "cannot open shared object" in str(e):
        pytest.skip(f"GUI libraries not available: {e}", allow_module_level=True)
    else:
        raise

# Ensure pc_controller/src is on path (pytest.ini usually does this)
sys.path.append(os.path.join(os.getcwd(), "pc_controller", "src"))

# Force offscreen to avoid GUI requirements in CI
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")


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


def test_gui_manager_tabs_and_actions(qapp, monkeypatch):
    # Import after setting env to ensure Qt picks offscreen
    from gui import gui_manager as gm  # type: ignore

    # Prevent local hardware interfaces from starting threads
    monkeypatch.setattr(gm, "WebcamInterface", None, raising=False)
    monkeypatch.setattr(gm, "ShimmerInterface", None, raising=False)

    net = _StubNetwork()
    ui = gm.GUIManager(network=net)
    try:
        # Verify tabs
        tab_names = [ui.tabs.tabText(i) for i in range(ui.tabs.count())]
        assert any("Dashboard" in t for t in tab_names)
        assert any("Logs" in t for t in tab_names)
        assert any("Playback" in t for t in tab_names)

        # Verify toolbar actions exist
        assert hasattr(ui, "act_start")
        assert hasattr(ui, "act_stop")
        assert hasattr(ui, "act_flash")
        assert hasattr(ui, "act_connect")

        # Verify stub network started
        assert net.started is True
    finally:
        ui.close()
