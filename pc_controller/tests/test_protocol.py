"""Unit tests for protocol helpers (Phase 1-4).

These tests avoid PyQt6 imports by testing only the pure-protocol helpers.
"""
from __future__ import annotations

from pc_controller.src.network.protocol import (
    build_query_capabilities,
    build_start_recording,
    build_stop_recording,
    build_flash_sync,
    build_time_sync_request,
    build_transfer_files,
    compute_time_sync,
    parse_json_line,
    QUERY_CMD_ID,
    COMMAND_QUERY_CAPABILITIES,
    COMMAND_START_RECORDING,
    COMMAND_STOP_RECORDING,
    COMMAND_FLASH_SYNC,
    COMMAND_TIME_SYNC,
    COMMAND_TRANSFER_FILES,
)


def test_build_query_capabilities_format() -> None:
    line = build_query_capabilities()
    assert line.endswith("\n"), "Message must end with a newline for line-oriented protocol"
    payload = parse_json_line(line.strip("\n"))
    assert payload["id"] == QUERY_CMD_ID
    assert payload["command"] == COMMAND_QUERY_CAPABILITIES


def test_build_start_stop_flash_and_time_sync() -> None:
    start = build_start_recording("sess123", 2)
    p = parse_json_line(start.strip())
    assert p["command"] == COMMAND_START_RECORDING and p["session_id"] == "sess123" and p["id"] == 2

    stop = build_stop_recording(3)
    p = parse_json_line(stop.strip())
    assert p["command"] == COMMAND_STOP_RECORDING and p["id"] == 3

    flash = build_flash_sync(4)
    p = parse_json_line(flash.strip())
    assert p["command"] == COMMAND_FLASH_SYNC and p["id"] == 4

    ts = build_time_sync_request(5, 1234567890)
    p = parse_json_line(ts.strip())
    assert p["command"] == COMMAND_TIME_SYNC and p["id"] == 5 and p["t0"] == 1234567890


def test_compute_time_sync_ntp_math() -> None:
    # Example values: t0 (PC send), t1 (Android recv), t2 (Android send), t3 (PC recv)
    t0, t1, t2, t3 = 1000, 1500, 1600, 2000
    offset, delay = compute_time_sync(t0, t1, t2, t3)
    # offset = ((1500-1000) + (1600-2000)) / 2 = (500 - 400)/2 = 50
    # delay = (2000 - 1000) - (1600 - 1500) = 1000 - 100 = 900
    assert offset == 50 and delay == 900


def test_parse_json_line_roundtrip() -> None:
    original = {"ack_id": 1, "status": "ok", "capabilities": {"has_thermal": False}}
    text = str(original).replace("'", '"')  # naive JSON from dict
    parsed = parse_json_line(text)
    assert parsed == original
