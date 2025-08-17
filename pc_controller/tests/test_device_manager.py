from __future__ import annotations

import time

from pc_controller.src.core.device_manager import DeviceManager


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
