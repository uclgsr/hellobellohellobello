"""
Main entry point for the PC Controller (Hub) application.

Phase 1 goal: initialize the minimal PyQt6 application shell, start the
NetworkController responsible for Zeroconf discovery and establishing
connections to Android Spokes, and log capability handshakes.

This module intentionally keeps GUI light for Phase 1; the focus is the
core communication layer. GUI will be expanded in later phases.
"""
from __future__ import annotations

import sys
from PyQt6.QtWidgets import QApplication

from gui.gui_manager import GUIManager
from network.network_controller import NetworkController


def main() -> int:
    app = QApplication(sys.argv)

    network = NetworkController()
    gui = GUIManager(network)

    gui.show()
    code = app.exec()

    # Ensure proper shutdown
    network.shutdown()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
