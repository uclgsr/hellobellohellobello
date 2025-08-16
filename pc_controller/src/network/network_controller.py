"""NetworkController for the PC Controller (Hub).

Responsibilities in Phase 1:
- Browse for Android Spoke services via Zeroconf/mDNS.
- Allow connecting to a discovered device over TCP in a background QThread.
- Send an initial JSON command {"id": 1, "command": "query_capabilities"}
  and receive the response, emitting it back to the GUI.

Phase 4 extensions:
- Broadcast start/stop/flash commands with session management.
- Perform NTP-like time synchronization handshake and store per-device offsets.

Note: The Android Spoke advertises service type: _gsr-controller._tcp.local.
"""
from __future__ import annotations

import base64
import json
import socket
import time
from PyQt6.QtCore import QObject, QThread, pyqtSignal
from dataclasses import dataclass
from typing import Dict, Optional
from zeroconf import IPVersion, ServiceBrowser, Zeroconf

from .protocol import (
    build_query_capabilities,
    build_start_recording,
    build_stop_recording,
    build_flash_sync,
    build_time_sync_request,
    compute_time_sync,
    build_transfer_files,
)

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
                data = build_query_capabilities().encode("utf-8")
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


class _BroadcastWorker(QThread):
    """Send commands to all devices in a background thread.

    Optionally performs a time sync handshake prior to sending start.
    For transfer_files, it sends host/port/session_id to each device.
    """

    log = pyqtSignal(str)

    def __init__(self, devices: Dict[str, DiscoveredDevice], command: str, session_id: Optional[str] = None,
                 controller: "NetworkController" = None, timeout: float = 5.0,
                 receiver_host: Optional[str] = None, receiver_port: Optional[int] = None) -> None:
        super().__init__()
        self._devices = dict(devices)
        self._command = command
        self._session_id = session_id
        self._timeout = timeout
        self._controller = controller
        self._receiver_host = receiver_host
        self._receiver_port = receiver_port

    def _read_line(self, sock: socket.socket) -> str:
        chunks: list[bytes] = []
        while True:
            b = sock.recv(4096)
            if not b:
                break
            chunks.append(b)
            if b"\n" in b:
                break
        return b"".join(chunks).split(b"\n", 1)[0].decode("utf-8", errors="replace")

    def run(self) -> None:  # noqa: D401
        try:
            for name, dev in self._devices.items():
                try:
                    with socket.create_connection((dev.address, dev.port), timeout=self._timeout) as sock:
                        sock.settimeout(self._timeout)
                        # optional time sync before start
                        if self._command == "start_recording":
                            t0 = time.time_ns()
                            sock.sendall(build_time_sync_request(100, t0).encode("utf-8"))
                            raw = self._read_line(sock)
                            t3 = time.time_ns()
                            payload = json.loads(raw)
                            t1 = int(payload.get("t1", 0))
                            t2 = int(payload.get("t2", 0))
                            offset, delay = compute_time_sync(t0, t1, t2, t3)
                            if self._controller is not None:
                                self._controller._store_offset(name, offset)
                            self.log.emit(f"Time sync {name}: offset={offset}ns delay={delay}ns")
                        # send the command
                        msg_id = int(time.time()*1000) % 2_000_000_000
                        if self._command == "start_recording" and self._session_id:
                            msg = build_start_recording(self._session_id, msg_id)
                        elif self._command == "stop_recording":
                            msg = build_stop_recording(msg_id)
                        elif self._command == "flash_sync":
                            msg = build_flash_sync(msg_id)
                        elif self._command == "transfer_files" and self._session_id and self._receiver_host and self._receiver_port:
                            msg = build_transfer_files(self._receiver_host, int(self._receiver_port), self._session_id, msg_id)
                        else:
                            self.log.emit(f"Unknown or incomplete command {self._command}")
                            continue
                        sock.sendall(msg.encode("utf-8"))
                        # read optional ack without blocking overall process too long
                        try:
                            raw = self._read_line(sock)
                            if raw:
                                self.log.emit(f"Ack from {name}: {raw}")
                        except Exception:
                            pass
                except Exception as exc:  # noqa: BLE001
                    self.log.emit(f"Broadcast to {name} failed: {exc}")
        except Exception as exc:  # noqa: BLE001
            self.log.emit(f"Broadcast worker error: {exc}")


class PreviewStreamWorker(QThread):
    """Maintains a persistent TCP connection to receive asynchronous messages.

    Specifically listens for type=="preview_frame" messages and emits frames.
    """

    log = pyqtSignal(str)
    frame = pyqtSignal(str, bytes, int)  # device name, jpeg bytes, ts

    def __init__(self, device: DiscoveredDevice, timeout: float = 5.0) -> None:
        super().__init__()
        self._device = device
        self._timeout = timeout
        self._stopped = False

    def stop(self) -> None:
        self._stopped = True

    def run(self) -> None:  # noqa: D401
        while not self._stopped:
            try:
                with socket.create_connection((self._device.address, self._device.port), timeout=self._timeout) as sock:
                    sock.settimeout(self._timeout)
                    buf = b""
                    while not self._stopped:
                        chunk = sock.recv(4096)
                        if not chunk:
                            break
                        buf += chunk
                        while b"\n" in buf:
                            line, buf = buf.split(b"\n", 1)
                            text = line.decode("utf-8", errors="replace")
                            try:
                                payload = json.loads(text)
                            except Exception:
                                continue
                            # If this is a preview frame, decode and emit
                            if isinstance(payload, dict) and payload.get("type") == "preview_frame":
                                b64 = payload.get("jpeg_base64", "")
                                ts = int(payload.get("ts", 0))
                                try:
                                    data = base64.b64decode(b64)
                                    self.frame.emit(self._device.name, data, ts)
                                except Exception:
                                    pass
                            else:
                                # Other messages ignored or logged
                                pass
                # Socket closed; retry after short delay
                time.sleep(1.0)
            except Exception as exc:  # noqa: BLE001
                self.log.emit(f"Stream error for {self._device.name}: {exc}")
                time.sleep(2.0)


class NetworkController(QObject):
    """Coordinates network discovery and connections for the PC Hub."""

    device_discovered = pyqtSignal(DiscoveredDevice)
    device_removed = pyqtSignal(str)  # name
    log = pyqtSignal(str)
    device_capabilities = pyqtSignal(str, dict)  # name, payload
    preview_frame = pyqtSignal(str, bytes, int)  # device name, jpeg bytes, ts

    def __init__(self) -> None:
        super().__init__()
        self._zeroconf = Zeroconf(ip_version=IPVersion.All)
        self._browser: Optional[ServiceBrowser] = None
        self._listener = _ZeroconfListener(self)
        self._devices: Dict[str, DiscoveredDevice] = {}
        self._workers: Dict[str, ConnectionWorker] = {}
        self._stream_workers: Dict[str, PreviewStreamWorker] = {}
        self._clock_offsets_ns: Dict[str, int] = {}

    def get_clock_offsets(self) -> Dict[str, int]:
        """Return a copy of the last known per-device clock offsets (ns)."""
        return dict(self._clock_offsets_ns)

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
        # Start preview stream worker for this device
        try:
            worker = PreviewStreamWorker(device)
            worker.log.connect(self._emit_log)
            worker.frame.connect(self._on_preview_frame)
            self._stream_workers[device.name] = worker
            worker.start()
            self._emit_log(f"PreviewStreamWorker started for {device.name}")
        except Exception as exc:
            self._emit_log(f"Failed to start PreviewStreamWorker for {device.name}: {exc}")

    def _on_service_removed(self, name: str) -> None:
        clean_name = name.rstrip(".")
        # Stop preview stream worker if running
        try:
            worker = self._stream_workers.pop(clean_name, None)
            if worker is not None:
                worker.stop()
                worker.wait(1000)
                self._emit_log(f"PreviewStreamWorker stopped for {clean_name}")
        except Exception:
            pass
        if clean_name in self._devices:
            del self._devices[clean_name]
        self.device_removed.emit(clean_name)

    def _emit_log(self, message: str) -> None:
        self.log.emit(message)

    def _on_preview_frame(self, name: str, data: bytes, ts: int) -> None:
        # Re-emit for GUI consumers
        try:
            self.preview_frame.emit(name, data, ts)
        except Exception:
            pass

    def _store_offset(self, name: str, offset_ns: int) -> None:
        self._clock_offsets_ns[name] = offset_ns

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

    # Phase 4 broadcast API
    def broadcast_start_recording(self, session_id: str) -> None:
        worker = _BroadcastWorker(self._devices, "start_recording", session_id, controller=self)
        worker.log.connect(self._emit_log)
        worker.start()
        self._emit_log(f"Broadcast start_recording for session {session_id}")

    def broadcast_stop_recording(self) -> None:
        worker = _BroadcastWorker(self._devices, "stop_recording", controller=self)
        worker.log.connect(self._emit_log)
        worker.start()
        self._emit_log("Broadcast stop_recording")

    def broadcast_transfer_files(self, host: str, port: int, session_id: str) -> None:
        """Broadcast a transfer_files command with receiver host/port and session id."""
        worker = _BroadcastWorker(
            self._devices,
            "transfer_files",
            session_id=session_id,
            controller=self,
            receiver_host=host,
            receiver_port=port,
        )
        worker.log.connect(self._emit_log)
        worker.start()
        self._emit_log(f"Broadcast transfer_files to {host}:{port} for session {session_id}")

    def broadcast_flash_sync(self) -> None:
        worker = _BroadcastWorker(self._devices, "flash_sync", controller=self)
        worker.log.connect(self._emit_log)
        worker.start()
        self._emit_log("Broadcast flash_sync")
