import os
import pytest
import sys

# Skip if PyQt6 is not available
PyQt6 = pytest.importorskip("PyQt6")  # noqa: N816 (external import name)

from PyQt6.QtCore import QObject, pyqtSignal
from PyQt6.QtWidgets import QApplication

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
