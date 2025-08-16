"""NetworkController for the PC Controller (Hub).

Responsibilities in Phase 1:
- Browse for Android Spoke services via Zeroconf/mDNS.
- Allow connecting to a discovered device over TCP in a background QThread.
- Send an initial JSON command {"id": 1, "command": "query_capabilities"}
  and receive the response, emitting it back to the GUI.

Note: The Android Spoke advertises service type: _gsr-controller._tcp.local.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import socket
from typing import Dict, Optional

from PyQt6.QtCore import QObject, QThread, pyqtSignal
from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, Zeroconf


SERVICE_TYPE = "_gsr-controller._tcp.local."


@dataclass(frozen=True)
class DiscoveredDevice:
    name: str
    address: str
    port: int


class _ZeroconfListener:
    """Listener object for Zeroconf service browser."""

    def __init__(self, parent: "NetworkController") -> None:
        self._parent = parent

    def remove_service(self, zeroconf: Zeroconf, type_: str, name: str) -> None:  # noqa: D401
        self._parent._on_service_removed(name)

    def add_service(self, zeroconf: Zeroconf, type_: str, name: str) -> None:  # noqa: D401
        info = zeroconf.get_service_info(type_, name, 5000)
        if not info:
            self._parent._emit_log(f"Service resolved empty: {name}")
            return
        addresses = info.parsed_scoped_addresses()
        if not addresses:
            self._parent._emit_log(f"No addresses for: {name}")
            return
        address = addresses[0]
        device = DiscoveredDevice(name=name.rstrip("."), address=address, port=info.port)
        self._parent._on_service_added(device)


class ConnectionWorker(QThread):
    """Background worker to manage a TCP connection to a device.

    On start, connects to the device, sends the query_capabilities command,
    and waits for a single JSON line response. Emits signals for logs and
    capabilities received, then exits.
    """

    log = pyqtSignal(str)
    capabilities = pyqtSignal(str, dict)  # device name, capabilities dict

    def __init__(self, device: DiscoveredDevice, timeout: float = 5.0) -> None:
        super().__init__()
        self._device = device
        self._timeout = timeout

    def run(self) -> None:  # noqa: D401
        try:
            with socket.create_connection((self._device.address, self._device.port), timeout=self._timeout) as sock:
                sock.settimeout(self._timeout)
                # Send query
                message = {"id": 1, "command": "query_capabilities"}
                data = (json.dumps(message) + "\n").encode("utf-8")
                sock.sendall(data)
                self.log.emit(f"Sent query_capabilities to {self._device.name}")

                # Receive single line
                chunks: list[bytes] = []
                while True:
                    b = sock.recv(4096)
                    if not b:
                        break
                    chunks.append(b)
                    if b"\n" in b:
                        break
                raw = b"".join(chunks).split(b"\n", 1)[0].decode("utf-8", errors="replace")
                self.log.emit(f"Received: {raw}")
                try:
                    payload = json.loads(raw)
                except json.JSONDecodeError as exc:  # noqa: BLE001
                    self.log.emit(f"Failed to parse JSON: {exc}")
                    return
                self.capabilities.emit(self._device.name, payload)
        except Exception as exc:  # noqa: BLE001
            self.log.emit(f"Connection error to {self._device.name}: {exc}")


class NetworkController(QObject):
    """Coordinates network discovery and connections for the PC Hub."""

    device_discovered = pyqtSignal(DiscoveredDevice)
    device_removed = pyqtSignal(str)  # name
    log = pyqtSignal(str)
    device_capabilities = pyqtSignal(str, dict)  # name, payload

    def __init__(self) -> None:
        super().__init__()
        self._zeroconf = Zeroconf(ip_version=IPVersion.All)
        self._browser: Optional[ServiceBrowser] = None
        self._listener = _ZeroconfListener(self)
        self._devices: Dict[str, DiscoveredDevice] = {}
        self._workers: Dict[str, ConnectionWorker] = {}

    def start(self) -> None:
        if self._browser is None:
            self._browser = ServiceBrowser(self._zeroconf, SERVICE_TYPE, self._listener)
            self._emit_log("Service discovery started.")

    def shutdown(self) -> None:
        # Stop workers
        for name, worker in list(self._workers.items()):
            if worker.isRunning():
                worker.quit()
                worker.wait(1000)
        self._workers.clear()
        # Stop discovery
        if self._browser is not None:
            self._browser.cancel()
            self._browser = None
        # Close zeroconf
        try:
            self._zeroconf.close()
        except Exception:  # pragma: no cover - safety
            pass

    # Internal events from Zeroconf listener
    def _on_service_added(self, device: DiscoveredDevice) -> None:
        self._devices[device.name] = device
        self.device_discovered.emit(device)

    def _on_service_removed(self, name: str) -> None:
        clean_name = name.rstrip(".")
        if clean_name in self._devices:
            del self._devices[clean_name]
        self.device_removed.emit(clean_name)

    def _emit_log(self, message: str) -> None:
        self.log.emit(message)

    # Public API
    def connect_to_device(self, name: str, address: str, port: int) -> None:
        device = DiscoveredDevice(name=name, address=address, port=port)
        worker = ConnectionWorker(device)
        worker.log.connect(self._emit_log)
        worker.capabilities.connect(self._on_capabilities)
        self._workers[name] = worker
        worker.start()
        self._emit_log(f"ConnectionWorker started for {name}")

    def _on_capabilities(self, name: str, payload: dict) -> None:
        self.device_capabilities.emit(name, payload)
        self._emit_log(f"Capabilities from {name}: {payload}")
