"""GUI Manager for the PC Controller (Hub).

Phase 1: Provide a minimal UI that lists discovered devices and allows
initiating a connection to perform the initial capabilities handshake.

The full Dashboard/Logs/Playback interface will be implemented in later
phases. For now, we only show a simple window with device list and logs.
"""
from __future__ import annotations

from typing import List

from PyQt6.QtCore import pyqtSlot
from PyQt6.QtWidgets import (
    QWidget,
    QVBoxLayout,
    QListWidget,
    QPushButton,
    QTextEdit,
    QLabel,
)

from network.network_controller import DiscoveredDevice, NetworkController


class GUIManager(QWidget):
    """Minimal GUI to interact with the NetworkController in Phase 1."""

    def __init__(self, network: NetworkController) -> None:
        super().__init__()
        self.setWindowTitle("PC Controller - Phase 1")
        self._network = network

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Discovered Devices"))

        self.device_list = QListWidget(self)
        layout.addWidget(self.device_list)

        self.connect_btn = QPushButton("Connect to Selected", self)
        layout.addWidget(self.connect_btn)

        layout.addWidget(QLabel("Logs"))
        self.logs = QTextEdit(self)
        self.logs.setReadOnly(True)
        layout.addWidget(self.logs)

        # Wire signals
        self.connect_btn.clicked.connect(self._on_connect_clicked)
        self._network.device_discovered.connect(self._on_device_discovered)
        self._network.device_removed.connect(self._on_device_removed)
        self._network.log.connect(self._on_log)

        # Start discovery
        self._network.start()

    @pyqtSlot(DiscoveredDevice)
    def _on_device_discovered(self, device: DiscoveredDevice) -> None:
        self.logs.append(f"Discovered: {device.name} @ {device.address}:{device.port}")
        self.device_list.addItem(f"{device.name} | {device.address}:{device.port}")

    @pyqtSlot(str)
    def _on_device_removed(self, name: str) -> None:
        self.logs.append(f"Removed: {name}")
        for i in range(self.device_list.count()):
            if self.device_list.item(i).text().startswith(name + " "):
                self.device_list.takeItem(i)
                break

    @pyqtSlot(str)
    def _on_log(self, message: str) -> None:
        self.logs.append(message)

    @pyqtSlot()
    def _on_connect_clicked(self) -> None:
        item = self.device_list.currentItem()
        if not item:
            self.logs.append("No device selected.")
            return
        text = item.text()
        try:
            name, addr_port = text.split(" | ", 1)
            address, port_str = addr_port.split(":")
            port = int(port_str)
        except Exception as exc:  # noqa: BLE001 - user-facing parsing
            self.logs.append(f"Failed to parse selection: {exc}")
            return

        self.logs.append(f"Connecting to {name} @ {address}:{port} ...")
        self._network.connect_to_device(name=name, address=address, port=port)
