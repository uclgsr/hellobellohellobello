"""Tests for HeartbeatManager functionality."""
import asyncio
import json
from unittest.mock import patch

import pytest

from pc_controller.src.network.heartbeat_manager import HeartbeatManager, HeartbeatStatus


class TestHeartbeatStatus:
    """Test HeartbeatStatus dataclass functionality."""

    def test_heartbeat_status_initialization(self):
        """Test HeartbeatStatus is properly initialized."""
        status = HeartbeatStatus(device_id="test_device", last_heartbeat_ns=123456789)
        assert status.device_id == "test_device"
        assert status.last_heartbeat_ns == 123456789
        assert status.consecutive_misses == 0
        assert status.is_healthy is True
        assert status.reconnection_attempts == 0

    def test_update_heartbeat_resets_counters(self):
        """Test that updating heartbeat resets miss counter."""
        status = HeartbeatStatus(device_id="test", last_heartbeat_ns=0)
        status.consecutive_misses = 5
        status.is_healthy = False

        with patch('time.time_ns', return_value=999999999):
            status.update_heartbeat()

        assert status.last_heartbeat_ns == 999999999
        assert status.consecutive_misses == 0
        assert status.is_healthy is True

    def test_mark_missed_updates_counters(self):
        """Test that marking missed heartbeats updates counters."""
        status = HeartbeatStatus(device_id="test", last_heartbeat_ns=0)

        # First two misses should not mark as unhealthy
        status.mark_missed()
        status.mark_missed()
        assert status.consecutive_misses == 2
        assert status.is_healthy is True

        # Third miss should mark as unhealthy
        status.mark_missed()
        assert status.consecutive_misses == 3
        assert status.is_healthy is False


class TestHeartbeatManager:
    """Test HeartbeatManager functionality."""

    def test_manager_initialization(self):
        """Test HeartbeatManager initializes with correct defaults."""
        manager = HeartbeatManager()
        assert manager.heartbeat_interval_s == 3.0
        assert manager.timeout_ns == 9_000_000_000  # 3 * 3 * 1e9
        assert manager.max_reconnect_attempts == 10
        assert len(manager._devices) == 0

    def test_register_device(self):
        """Test device registration."""
        manager = HeartbeatManager()

        with patch('time.time_ns', return_value=123456789):
            manager.register_device("device1")

        assert "device1" in manager._devices
        status = manager.get_device_status("device1")
        assert status is not None
        assert status.device_id == "device1"
        assert status.last_heartbeat_ns == 123456789

    def test_unregister_device(self):
        """Test device unregistration."""
        manager = HeartbeatManager()
        manager.register_device("device1")

        assert "device1" in manager._devices
        manager.unregister_device("device1")
        assert "device1" not in manager._devices

    def test_record_heartbeat_auto_registers(self):
        """Test that recording heartbeat auto-registers device."""
        manager = HeartbeatManager()

        with patch('time.time_ns', return_value=987654321):
            manager.record_heartbeat("new_device")

        assert "new_device" in manager._devices
        status = manager.get_device_status("new_device")
        assert status.last_heartbeat_ns == 987654321

    def test_record_heartbeat_triggers_online_callback(self):
        """Test that heartbeat triggers online callback when device recovers."""
        manager = HeartbeatManager()
        callback_called = False

        def online_callback(device_id):
            nonlocal callback_called
            callback_called = True
            assert device_id == "device1"

        manager.register_device("device1")
        manager.set_device_online_callback("device1", online_callback)

        # Mark device as unhealthy
        status = manager.get_device_status("device1")
        status.is_healthy = False

        # Record heartbeat should trigger online callback
        manager.record_heartbeat("device1")
        assert callback_called

    def test_get_healthy_unhealthy_devices(self):
        """Test getting healthy and unhealthy device sets."""
        manager = HeartbeatManager()
        manager.register_device("healthy1")
        manager.register_device("healthy2")
        manager.register_device("unhealthy1")

        # Mark one device as unhealthy
        manager._devices["unhealthy1"].is_healthy = False

        healthy = manager.get_healthy_devices()
        unhealthy = manager.get_unhealthy_devices()

        assert healthy == {"healthy1", "healthy2"}
        assert unhealthy == {"unhealthy1"}

    @pytest.mark.asyncio
    async def test_start_stop_monitoring(self):
        """Test starting and stopping the monitoring loop."""
        manager = HeartbeatManager(heartbeat_interval_s=0.1)  # Fast for testing

        assert not manager._running

        await manager.start_monitoring()
        assert manager._running
        assert manager._monitor_task is not None

        await manager.stop_monitoring()
        assert not manager._running

    @pytest.mark.asyncio
    async def test_check_heartbeats_marks_devices_offline(self):
        """Test that missed heartbeats mark devices as offline."""
        manager = HeartbeatManager(heartbeat_interval_s=0.1, timeout_multiplier=2)

        offline_callback_called = False
        def offline_callback(device_id):
            nonlocal offline_callback_called
            offline_callback_called = True
            assert device_id == "test_device"

        manager.set_device_offline_callback("test_device", offline_callback)

        # Set heartbeat time to past to simulate timeout - register device at specific time
        with patch('time.time_ns', return_value=1000000000):  # 1 second
            manager.register_device("test_device")

        # Check heartbeats after timeout period
        with patch('time.time_ns', return_value=1300000000):  # 1.3 seconds later
            await manager._check_heartbeats()

        status = manager.get_device_status("test_device")
        assert status.consecutive_misses == 1
        assert status.is_healthy  # Still healthy after just 1 miss
        assert not offline_callback_called  # Callback not triggered yet

        # Check heartbeats again to accumulate more misses
        with patch('time.time_ns', return_value=1600000000):  # 1.6 seconds later
            await manager._check_heartbeats()

        status = manager.get_device_status("test_device")
        assert status.consecutive_misses == 2
        assert status.is_healthy  # Still healthy after 2 misses

        # Third miss should trigger offline status
        with patch('time.time_ns', return_value=1900000000):  # 1.9 seconds later
            await manager._check_heartbeats()

        status = manager.get_device_status("test_device")
        assert status.consecutive_misses == 3
        assert not status.is_healthy  # Now unhealthy after 3 misses
        assert offline_callback_called  # Callback triggered

    @pytest.mark.asyncio
    async def test_check_heartbeats_triggers_reconnect(self):
        """Test that missed heartbeats trigger reconnection."""
        manager = HeartbeatManager(
            heartbeat_interval_s=0.1,
            timeout_multiplier=2,
            reconnect_backoff_s=0.1
        )

        reconnect_callback_called = False
        def reconnect_callback(device_id):
            nonlocal reconnect_callback_called
            reconnect_callback_called = True
            assert device_id == "test_device"

        manager.register_device("test_device")
        manager.set_reconnect_callback("test_device", reconnect_callback)

        # Set up initial state - device offline for sufficient time
        status = manager.get_device_status("test_device")
        with patch('time.time_ns', return_value=1000000000):  # 1 second ago
            status.last_heartbeat_ns = 800000000  # 0.8 seconds
            status.is_healthy = False
            status.consecutive_misses = 5
            status.last_reconnect_attempt_ns = 900000000  # 0.9 seconds

        # Check heartbeats - should trigger reconnect
        with patch('time.time_ns', return_value=1200000000):  # 1.2 seconds
            await manager._check_heartbeats()

        assert reconnect_callback_called
        assert status.reconnection_attempts > 0

    def test_create_heartbeat_message(self):
        """Test heartbeat message creation."""
        manager = HeartbeatManager()

        with patch('time.time_ns', return_value=123456789):
            message = manager.create_heartbeat_message("device1", {"battery": 85})

        data = json.loads(message)
        assert data["v"] == 1
        assert data["type"] == "heartbeat"
        assert data["device_id"] == "device1"
        assert data["timestamp_ns"] == 123456789
        assert data["metadata"]["battery"] == 85

    def test_parse_heartbeat_message(self):
        """Test heartbeat message parsing."""
        manager = HeartbeatManager()

        valid_message = json.dumps({
            "v": 1,
            "type": "heartbeat",
            "device_id": "device1",
            "timestamp_ns": 123456789,
            "metadata": {"battery": 85}
        })

        parsed = manager.parse_heartbeat_message(valid_message)
        assert parsed is not None
        assert parsed["device_id"] == "device1"
        assert parsed["metadata"]["battery"] == 85

        # Test invalid message
        invalid_message = json.dumps({"type": "other", "device_id": "device1"})
        parsed = manager.parse_heartbeat_message(invalid_message)
        assert parsed is None

        # Test malformed JSON
        parsed = manager.parse_heartbeat_message("invalid json")
        assert parsed is None

    def test_get_status_summary(self):
        """Test getting status summary."""
        manager = HeartbeatManager()
        manager.register_device("device1")
        manager.register_device("device2")

        # Make one device unhealthy
        manager._devices["device2"].is_healthy = False
        manager._devices["device2"].consecutive_misses = 3

        summary = manager.get_status_summary()

        assert summary["total_devices"] == 2
        assert "device1" in summary["healthy_devices"]
        assert "device2" in summary["unhealthy_devices"]
        assert summary["heartbeat_interval_s"] == 3.0

        # Check device details
        assert "device1" in summary["device_details"]
        assert "device2" in summary["device_details"]
        assert summary["device_details"]["device2"]["consecutive_misses"] == 3
        assert not summary["device_details"]["device2"]["is_healthy"]


class TestHeartbeatIntegration:
    """Integration tests for heartbeat functionality."""

    @pytest.mark.asyncio
    async def test_full_heartbeat_cycle(self):
        """Test a complete heartbeat monitoring cycle."""
        # Use very short intervals for fast testing
        manager = HeartbeatManager(
            heartbeat_interval_s=0.01,
            timeout_multiplier=2,  # 0.02s timeout
            reconnect_backoff_s=0.01
        )

        # Track callback invocations
        events = []

        def offline_callback(device_id):
            events.append(f"offline:{device_id}")

        def online_callback(device_id):
            events.append(f"online:{device_id}")

        def reconnect_callback(device_id):
            events.append(f"reconnect:{device_id}")

        # Register device with callbacks at a specific time
        start_time = 1000000000  # 1 second in nanoseconds
        with patch('time.time_ns', return_value=start_time):
            manager.register_device("test_device")
            manager.set_device_offline_callback("test_device", offline_callback)
            manager.set_device_online_callback("test_device", online_callback)
            manager.set_reconnect_callback("test_device", reconnect_callback)

        # Start monitoring
        await manager.start_monitoring()

        # Wait for multiple check cycles to ensure device goes offline
        # Need at least 3 misses for unhealthy status
        await asyncio.sleep(0.08)  # 8x the check interval

        # Device should be marked offline due to no recent heartbeats
        unhealthy_devices = manager.get_unhealthy_devices()
        assert "test_device" in unhealthy_devices, (
            f"Expected test_device in {unhealthy_devices}, events: {events}"
        )
        assert "offline:test_device" in events, f"Expected offline event in {events}"

        # Send a heartbeat to restore connection
        manager.record_heartbeat("test_device")

        # Device should be back online
        healthy_devices = manager.get_healthy_devices()
        assert "test_device" in healthy_devices, f"Expected test_device in {healthy_devices}"
        assert "online:test_device" in events, f"Expected online event in {events}"

        await manager.stop_monitoring()

    def test_heartbeat_message_roundtrip(self):
        """Test creating and parsing heartbeat messages."""
        manager = HeartbeatManager()

        # Create message
        metadata = {"battery": 75, "recording": True}
        message = manager.create_heartbeat_message("device1", metadata)

        # Parse message
        parsed = manager.parse_heartbeat_message(message)
        assert parsed is not None

        # Use parsed data to record heartbeat
        manager.record_heartbeat(parsed["device_id"], parsed["metadata"])

        # Verify device was registered and heartbeat recorded
        status = manager.get_device_status("device1")
        assert status is not None
        assert status.is_healthy
