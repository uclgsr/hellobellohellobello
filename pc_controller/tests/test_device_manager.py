from __future__ import annotations

import time
from unittest.mock import patch

from pc_controller.src.core.device_manager import DeviceInfo, DeviceManager


def test_heartbeat_updates_and_timeout() -> None:
    dm = DeviceManager(heartbeat_timeout_seconds=1)  # 1s timeout for fast test
    dev = "dev123"
    assert dm.get_status(dev) is None

    # First heartbeat registers and sets Online
    dm.update_heartbeat(dev)
    assert dm.get_status(dev) == "Online"

    # Before timeout, remains Online
    dm.check_timeouts(now_ns=time.time_ns())
    assert dm.get_status(dev) == "Online"

    # Simulate passage beyond timeout
    past = time.time_ns() + int(1.5 * 1e9)
    dm.check_timeouts(now_ns=past)
    assert dm.get_status(dev) == "Offline"

    # New heartbeat brings it back Online
    dm.update_heartbeat(dev)
    assert dm.get_status(dev) == "Online"


def test_device_registration_and_info() -> None:
    """Test device registration and info retrieval."""
    dm = DeviceManager(heartbeat_timeout_seconds=10)
    device_id = "test-device-001"
    
    # Initially no device registered
    assert dm.get_info(device_id) is None
    assert dm.get_status(device_id) is None
    
    # Register device explicitly
    dm.register(device_id)
    info = dm.get_info(device_id)
    assert info is not None
    assert info.device_id == device_id
    assert info.status == "Online"
    assert info.first_seen_ns > 0
    assert info.last_heartbeat_ns > 0
    
    # Multiple registrations don't create duplicates
    old_first_seen = info.first_seen_ns
    dm.register(device_id)
    info2 = dm.get_info(device_id)
    assert info2.first_seen_ns == old_first_seen  # Should not change


def test_device_status_management() -> None:
    """Test setting and getting device status."""
    dm = DeviceManager(heartbeat_timeout_seconds=5)
    device_id = "status-test-device"
    
    # Set status on unregistered device should register it first
    dm.set_status(device_id, "Recording")
    assert dm.get_status(device_id) == "Recording"
    assert dm.get_info(device_id) is not None
    
    # Change status
    dm.set_status(device_id, "Idle")
    assert dm.get_status(device_id) == "Idle"
    
    # Setting status updates heartbeat
    old_heartbeat = dm.get_info(device_id).last_heartbeat_ns
    time.sleep(0.001)  # Small delay to ensure timestamp difference
    dm.set_status(device_id, "Processing")
    new_heartbeat = dm.get_info(device_id).last_heartbeat_ns
    assert new_heartbeat > old_heartbeat


def test_device_removal() -> None:
    """Test device removal functionality."""
    dm = DeviceManager(heartbeat_timeout_seconds=5)
    device_id = "removable-device"
    
    # Register device
    dm.register(device_id)
    assert dm.get_status(device_id) == "Online"
    
    # Remove device
    dm.remove(device_id)
    assert dm.get_status(device_id) is None
    assert dm.get_info(device_id) is None
    
    # Removing non-existent device should not crash
    dm.remove("non-existent-device")  # Should not raise


def test_list_devices() -> None:
    """Test listing all registered devices."""
    dm = DeviceManager(heartbeat_timeout_seconds=10)
    
    # Initially empty
    assert len(dm.list_devices()) == 0
    
    # Add multiple devices
    devices = ["device-1", "device-2", "device-3"]
    for device_id in devices:
        dm.register(device_id)
    
    device_list = dm.list_devices()
    assert len(device_list) == 3
    for device_id in devices:
        assert device_id in device_list
        assert isinstance(device_list[device_id], DeviceInfo)


def test_timeout_property() -> None:
    """Test timeout seconds property."""
    dm = DeviceManager(heartbeat_timeout_seconds=7)
    assert dm.timeout_seconds == 7.0
    
    dm2 = DeviceManager(heartbeat_timeout_seconds=15)
    assert dm2.timeout_seconds == 15.0


def test_multiple_device_timeout_scenarios() -> None:
    """Test timeout scenarios with multiple devices."""
    dm = DeviceManager(heartbeat_timeout_seconds=2)
    
    # Register multiple devices at different times
    base_time = time.time_ns()
    
    dm.update_heartbeat("device-1")
    time.sleep(0.001)
    dm.update_heartbeat("device-2")
    time.sleep(0.001)
    dm.update_heartbeat("device-3")
    
    # All should be online initially
    assert dm.get_status("device-1") == "Online"
    assert dm.get_status("device-2") == "Online" 
    assert dm.get_status("device-3") == "Online"
    
    # Check timeouts at different intervals
    future_time = base_time + int(1.5 * 1e9)  # 1.5 seconds later
    dm.check_timeouts(now_ns=future_time)
    
    # All should still be online (within timeout)
    assert dm.get_status("device-1") == "Online"
    assert dm.get_status("device-2") == "Online"
    assert dm.get_status("device-3") == "Online"
    
    # Check after timeout
    timeout_time = base_time + int(3 * 1e9)  # 3 seconds later
    dm.check_timeouts(now_ns=timeout_time)
    
    # All should be offline now
    assert dm.get_status("device-1") == "Offline"
    assert dm.get_status("device-2") == "Offline"
    assert dm.get_status("device-3") == "Offline"


def test_device_info_to_dict() -> None:
    """Test DeviceInfo serialization."""
    device_info = DeviceInfo(
        device_id="test-device",
        first_seen_ns=1234567890000000000,
        last_heartbeat_ns=1234567891000000000,
        status="Recording"
    )
    
    result = device_info.to_dict()
    expected = {
        "device_id": "test-device",
        "first_seen_ns": 1234567890000000000,
        "last_heartbeat_ns": 1234567891000000000,
        "status": "Recording"
    }
    assert result == expected


@patch('pc_controller.src.core.device_manager.cfg_get')
def test_default_timeout_from_config(mock_cfg_get) -> None:
    """Test default timeout value from configuration."""
    # Test with config value
    mock_cfg_get.return_value = "30"
    dm = DeviceManager()
    assert dm.timeout_seconds == 30.0
    mock_cfg_get.assert_called_once_with("heartbeat_timeout_seconds", 10)
    
    # Test with default fallback
    mock_cfg_get.reset_mock()
    mock_cfg_get.return_value = 15
    dm2 = DeviceManager()
    assert dm2.timeout_seconds == 15.0


def test_edge_cases_and_boundary_conditions() -> None:
    """Test edge cases and boundary conditions."""
    # Test with very short timeout
    dm = DeviceManager(heartbeat_timeout_seconds=0.001)  # 1ms timeout
    device_id = "edge-case-device"
    
    dm.register(device_id)
    assert dm.get_status(device_id) == "Online"
    
    # Should timeout very quickly
    time.sleep(0.002)  # 2ms
    dm.check_timeouts()
    assert dm.get_status(device_id) == "Offline"
    
    # Test with zero timeout (edge case)
    dm_zero = DeviceManager(heartbeat_timeout_seconds=0)
    dm_zero.register("zero-timeout-device")
    dm_zero.check_timeouts()  # Should immediately timeout
    assert dm_zero.get_status("zero-timeout-device") == "Offline"
    
    # Test with very large timeout
    dm_large = DeviceManager(heartbeat_timeout_seconds=999999)
    dm_large.register("large-timeout-device")
    future_time = time.time_ns() + int(100 * 1e9)  # 100 seconds in the future
    dm_large.check_timeouts(now_ns=future_time)
    assert dm_large.get_status("large-timeout-device") == "Online"  # Should still be online


def test_concurrent_device_operations() -> None:
    """Test concurrent-like operations on device manager."""
    dm = DeviceManager(heartbeat_timeout_seconds=5)
    device_id = "concurrent-test-device"
    
    # Register, update heartbeat, set status in quick succession
    dm.register(device_id)
    dm.update_heartbeat(device_id)
    dm.set_status(device_id, "Processing")
    dm.update_heartbeat(device_id)
    
    # Final state should be consistent
    info = dm.get_info(device_id)
    assert info.status == "Online"  # update_heartbeat overwrites status to Online
    assert info.device_id == device_id


def test_empty_and_whitespace_device_ids() -> None:
    """Test handling of edge case device IDs."""
    dm = DeviceManager(heartbeat_timeout_seconds=10)
    
    # Test with empty string (should work but not recommended)
    dm.register("")
    assert dm.get_status("") == "Online"
    
    # Test with whitespace
    dm.register("   ")
    assert dm.get_status("   ") == "Online"
    
    # Ensure they are treated as different devices
    assert len(dm.list_devices()) == 2
