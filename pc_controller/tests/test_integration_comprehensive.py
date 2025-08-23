"""Comprehensive integration tests for Hub-Spoke communication and multi-component interactions.

This module focuses on testing the integration between different components of the system:
- Network communication between PC Controller and Android devices
- Data flow and synchronization
- Session management across multiple devices
- Error handling and recovery scenarios
- Performance under load
"""
from __future__ import annotations

import json
import socket
import threading
import time
from unittest.mock import MagicMock, patch

import pytest

from pc_controller.src.core.device_manager import DeviceManager
from pc_controller.src.core.session_manager import SessionManager
from pc_controller.src.network.heartbeat_manager import HeartbeatManager
from pc_controller.src.network.network_controller import NetworkController
from pc_controller.src.network.protocol import (
    COMMAND_QUERY_CAPABILITIES,
    COMMAND_START_RECORDING,
    COMMAND_STOP_RECORDING,
    build_query_capabilities,
    build_start_recording,
    build_stop_recording,
    parse_json_line,
)
from pc_controller.src.network.time_server import TimeSyncServer


class MockAndroidDevice:
    """Mock Android device for integration testing."""

    def __init__(self, device_id: str, host: str = "localhost", port: int = 0):
        self.device_id = device_id
        self.host = host
        self.port = port
        self.socket = None
        self.connected = False
        self.recording = False
        self.capabilities = {
            "device_id": device_id,
            "cameras": ["rgb", "thermal"],
            "sensors": ["gsr"],
            "version": "1.0.0"
        }

    def start_server(self) -> int:
        """Start mock Android device server. Returns the actual port."""
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind((self.host, self.port))
        self.socket.listen(1)
        actual_port = self.socket.getsockname()[1]

        def handle_client():
            while self.connected:
                try:
                    client, addr = self.socket.accept()
                    self._handle_connection(client)
                except socket.error:
                    break

        self.connected = True
        self.thread = threading.Thread(target=handle_client, daemon=True)
        self.thread.start()
        return actual_port

    def _handle_connection(self, client: socket.socket) -> None:
        """Handle incoming connection from PC Controller."""
        try:
            while self.connected:
                data = client.recv(1024)
                if not data:
                    break

                message = data.decode('utf-8').strip()
                if not message:
                    continue

                try:
                    parsed = parse_json_line(message)
                    response = self._handle_command(parsed)
                    if response:
                        client.send((json.dumps(response) + '\n').encode('utf-8'))
                except Exception as e:
                    # Send error response
                    error_response = {
                        "error": str(e),
                        "id": parsed.get("id", -1) if 'parsed' in locals() else -1
                    }
                    client.send((json.dumps(error_response) + '\n').encode('utf-8'))
        finally:
            client.close()

    def _handle_command(self, command: dict) -> dict:
        """Handle specific commands from PC Controller."""
        cmd_type = command.get("command")
        cmd_id = command.get("id", -1)

        if cmd_type == COMMAND_QUERY_CAPABILITIES:
            return {
                "id": cmd_id,
                "status": "success",
                "capabilities": self.capabilities
            }
        elif cmd_type == COMMAND_START_RECORDING:
            self.recording = True
            return {
                "id": cmd_id,
                "status": "recording_started",
                "session_id": command.get("session_id")
            }
        elif cmd_type == COMMAND_STOP_RECORDING:
            self.recording = False
            return {
                "id": cmd_id,
                "status": "recording_stopped"
            }
        else:
            return {
                "id": cmd_id,
                "status": "unknown_command",
                "command": cmd_type
            }

    def stop_server(self) -> None:
        """Stop the mock server."""
        self.connected = False
        if self.socket:
            self.socket.close()


class TestHubSpokeIntegration:
    """Test Hub-Spoke communication patterns."""

    def setup_method(self):
        """Set up test fixtures."""
        self.mock_devices = []
        self.device_manager = DeviceManager(heartbeat_timeout_seconds=5)
        self.session_manager = SessionManager()

    def teardown_method(self):
        """Clean up test fixtures."""
        for device in self.mock_devices:
            device.stop_server()

    def test_device_discovery_and_capability_exchange(self):
        """Test device discovery and capability exchange."""
        # Create mock Android device
        mock_device = MockAndroidDevice("test-device-001")
        port = mock_device.start_server()
        self.mock_devices.append(mock_device)

        # Give it time to start
        time.sleep(0.1)

        # Simulate device discovery and capability query
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as client:
            client.connect(("localhost", port))

            # Send capability query
            query = build_query_capabilities(1)
            client.send(query.encode('utf-8'))

            # Receive response
            response = client.recv(1024).decode('utf-8')
            parsed_response = json.loads(response.strip())

            assert parsed_response["id"] == 1
            assert parsed_response["status"] == "success"
            assert "capabilities" in parsed_response
            assert parsed_response["capabilities"]["device_id"] == "test-device-001"

    def test_synchronized_recording_start_stop(self):
        """Test synchronized recording across multiple devices."""
        # Create multiple mock devices
        devices = []
        for i in range(3):
            device = MockAndroidDevice(f"device-{i+1}")
            port = device.start_server()
            devices.append((device, port))
            self.mock_devices.append(device)

        time.sleep(0.1)  # Let servers start

        # Connect to all devices
        clients = []
        for device, port in devices:
            client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            client.connect(("localhost", port))
            clients.append(client)

        try:
            session_id = "test-session-123"

            # Send start recording to all devices
            for i, client in enumerate(clients):
                start_cmd = build_start_recording(session_id, i + 1)
                client.send(start_cmd.encode('utf-8'))

            # Verify all devices started recording
            for i, client in enumerate(clients):
                response = client.recv(1024).decode('utf-8')
                parsed = json.loads(response.strip())
                assert parsed["status"] == "recording_started"
                assert parsed["session_id"] == session_id
                assert devices[i][0].recording is True

            # Send stop recording to all devices
            for i, client in enumerate(clients):
                stop_cmd = build_stop_recording(i + 10)
                client.send(stop_cmd.encode('utf-8'))

            # Verify all devices stopped recording
            for i, client in enumerate(clients):
                response = client.recv(1024).decode('utf-8')
                parsed = json.loads(response.strip())
                assert parsed["status"] == "recording_stopped"
                assert devices[i][0].recording is False

        finally:
            for client in clients:
                client.close()

    def test_device_timeout_and_recovery(self):
        """Test device timeout detection and recovery."""
        device_id = "timeout-test-device"

        # Register device and update heartbeat
        self.device_manager.register(device_id)
        assert self.device_manager.get_status(device_id) == "Online"

        # Simulate timeout
        future_time = time.time_ns() + int(10 * 1e9)  # 10 seconds in future
        self.device_manager.check_timeouts(now_ns=future_time)
        assert self.device_manager.get_status(device_id) == "Offline"

        # Simulate recovery with heartbeat
        self.device_manager.update_heartbeat(device_id)
        assert self.device_manager.get_status(device_id) == "Online"

    def test_error_handling_in_communication(self):
        """Test error handling during Hub-Spoke communication."""
        mock_device = MockAndroidDevice("error-test-device")
        port = mock_device.start_server()
        self.mock_devices.append(mock_device)

        time.sleep(0.1)

        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as client:
            client.connect(("localhost", port))

            # Send malformed JSON
            client.send(b"invalid json data\n")

            # Should receive error response
            response = client.recv(1024).decode('utf-8')
            parsed = json.loads(response.strip())
            assert "error" in parsed

    def test_concurrent_device_communication(self):
        """Test concurrent communication with multiple devices."""
        num_devices = 5
        devices_and_ports = []

        # Create multiple devices
        for i in range(num_devices):
            device = MockAndroidDevice(f"concurrent-device-{i+1}")
            port = device.start_server()
            devices_and_ports.append((device, port))
            self.mock_devices.append(device)

        time.sleep(0.1)

        def communicate_with_device(device_port_tuple, device_index):
            device, port = device_port_tuple
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as client:
                client.connect(("localhost", port))

                # Query capabilities
                query = build_query_capabilities(device_index)
                client.send(query.encode('utf-8'))
                response = client.recv(1024).decode('utf-8')
                parsed = json.loads(response.strip())

                assert parsed["id"] == device_index
                assert parsed["status"] == "success"
                return parsed

        # Use threads to communicate concurrently
        import concurrent.futures
        with concurrent.futures.ThreadPoolExecutor(max_workers=num_devices) as executor:
            futures = []
            for i, device_port in enumerate(devices_and_ports):
                future = executor.submit(communicate_with_device, device_port, i + 1)
                futures.append(future)

            # Wait for all communications to complete
            results = []
            for future in concurrent.futures.as_completed(futures):
                results.append(future.result())

            # Verify all communications succeeded
            assert len(results) == num_devices
            for result in results:
                assert result["status"] == "success"


class TestMultiComponentIntegration:
    """Test integration between multiple system components."""

    @patch('pc_controller.src.network.time_server.socket.socket')
    def test_time_synchronization_integration(self, mock_socket_class):
        """Test time synchronization between Hub and Spokes."""
        # Mock UDP socket for time server
        mock_socket = MagicMock()
        mock_socket_class.return_value = mock_socket

        time_server = TimeSyncServer(port=12345)

        # Simulate time sync request
        client_data = b'{"type": "time_sync", "t0": 1234567890}'
        client_addr = ("192.168.1.100", 54321)

        mock_socket.recvfrom.return_value = (client_data, client_addr)

        # Start time server in thread
        import threading
        server_thread = threading.Thread(target=time_server.start, daemon=True)
        server_thread.start()

        time.sleep(0.1)  # Let server start

        # Verify response was sent
        mock_socket.sendto.assert_called()
        sent_data, sent_addr = mock_socket.sendto.call_args[0]

        assert sent_addr == client_addr
        response = json.loads(sent_data.decode('utf-8'))
        assert "t1" in response
        assert "t2" in response

    def test_session_manager_device_coordination(self):
        """Test session management coordinating multiple devices."""
        session_manager = SessionManager()
        device_manager = DeviceManager()

        # Register devices
        devices = ["device-1", "device-2", "device-3"]
        for device_id in devices:
            device_manager.register(device_id)
            device_manager.set_status(device_id, "Connected")

        # Start session
        session_id = session_manager.start_session()
        assert session_manager.is_active()

        # Simulate setting recording status for devices
        for device_id in devices:
            device_manager.set_status(device_id, "Recording")

        # Verify all devices are recording
        recording_devices = []
        for device_id in devices:
            if device_manager.get_status(device_id) == "Recording":
                recording_devices.append(device_id)

        assert len(recording_devices) == len(devices)

        # Stop session
        session_manager.stop_session()
        assert not session_manager.is_active()

    def test_heartbeat_manager_integration_with_device_manager(self):
        """Test heartbeat manager integration with device manager."""
        device_manager = DeviceManager(heartbeat_timeout_seconds=2)

        # Create heartbeat manager that updates device manager
        def heartbeat_callback(device_id: str):
            device_manager.update_heartbeat(device_id)

        heartbeat_manager = HeartbeatManager(callback=heartbeat_callback)

        # Send heartbeats
        devices = ["hb-device-1", "hb-device-2"]
        for device_id in devices:
            heartbeat_manager.record_heartbeat(device_id)

        # Verify devices are registered and online
        for device_id in devices:
            assert device_manager.get_status(device_id) == "Online"

        # Simulate timeout
        future_time = time.time_ns() + int(3 * 1e9)  # 3 seconds
        device_manager.check_timeouts(now_ns=future_time)

        # Verify devices timed out
        for device_id in devices:
            assert device_manager.get_status(device_id) == "Offline"


@pytest.mark.integration
class TestSystemIntegration:
    """System-level integration tests."""

    def test_end_to_end_recording_workflow(self):
        """Test complete end-to-end recording workflow."""
        # This would be a comprehensive test of the entire system
        # from device discovery to data export

        device_manager = DeviceManager()
        session_manager = SessionManager()

        # 1. Device Discovery Phase
        test_devices = ["android-1", "android-2"]
        for device_id in test_devices:
            device_manager.register(device_id)
            assert device_manager.get_status(device_id) == "Online"

        # 2. Capability Exchange Phase
        capabilities = {
            "android-1": {"cameras": ["rgb"], "sensors": ["gsr"]},
            "android-2": {"cameras": ["thermal"], "sensors": ["gsr"]}
        }

        # 3. Session Start Phase
        session_id = session_manager.start_session()
        assert session_manager.is_active()

        # 4. Recording Phase
        for device_id in test_devices:
            device_manager.set_status(device_id, "Recording")

        # Verify all devices are recording
        recording_count = sum(1 for device_id in test_devices
                            if device_manager.get_status(device_id) == "Recording")
        assert recording_count == len(test_devices)

        # 5. Session Stop Phase
        session_manager.stop_session()
        assert not session_manager.is_active()

        # 6. Cleanup Phase
        for device_id in test_devices:
            device_manager.set_status(device_id, "Idle")

    def test_fault_tolerance_and_recovery(self):
        """Test system fault tolerance and recovery mechanisms."""
        device_manager = DeviceManager(heartbeat_timeout_seconds=1)
        session_manager = SessionManager()

        # Start session with multiple devices
        devices = ["fault-device-1", "fault-device-2", "fault-device-3"]
        for device_id in devices:
            device_manager.register(device_id)
            device_manager.set_status(device_id, "Recording")

        session_id = session_manager.start_session()

        # Simulate one device failure (timeout)
        failed_device = devices[1]
        future_time = time.time_ns() + int(2 * 1e9)  # 2 seconds
        device_manager.check_timeouts(now_ns=future_time)

        # Verify failed device is offline
        assert device_manager.get_status(failed_device) == "Offline"

        # Other devices should still be recording
        for device_id in [devices[0], devices[2]]:
            assert device_manager.get_status(device_id) == "Recording"

        # Simulate device recovery
        device_manager.update_heartbeat(failed_device)
        device_manager.set_status(failed_device, "Recording")
        assert device_manager.get_status(failed_device) == "Online"

        # Session should still be active
        assert session_manager.is_active()

    def test_performance_under_load(self):
        """Test system performance with many devices and operations."""
        device_manager = DeviceManager(heartbeat_timeout_seconds=10)

        # Register many devices
        num_devices = 50
        devices = [f"perf-device-{i:03d}" for i in range(num_devices)]

        start_time = time.time()

        # Batch register devices
        for device_id in devices:
            device_manager.register(device_id)

        registration_time = time.time() - start_time

        # Batch heartbeat updates
        start_time = time.time()
        for device_id in devices:
            device_manager.update_heartbeat(device_id)

        heartbeat_time = time.time() - start_time

        # Verify all devices are online
        online_count = sum(1 for device_id in devices
                          if device_manager.get_status(device_id) == "Online")
        assert online_count == num_devices

        # Performance assertions (should handle 50 devices quickly)
        assert registration_time < 1.0  # Should register 50 devices in < 1s
        assert heartbeat_time < 1.0     # Should update 50 heartbeats in < 1s

        # Test timeout checking performance
        start_time = time.time()
        device_manager.check_timeouts()
        timeout_check_time = time.time() - start_time

        assert timeout_check_time < 0.1  # Should check 50 device timeouts in < 100ms


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
