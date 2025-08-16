"""Unit tests for Phase 1 protocol helpers.

These tests avoid PyQt6 imports by testing only the pure-protocol helpers.
"""
from __future__ import annotations

from pc_controller.src.network.protocol import (
    build_query_capabilities,
    parse_json_line,
    QUERY_CMD_ID,
    COMMAND_QUERY_CAPABILITIES,
)


def test_build_query_capabilities_format() -> None:
    line = build_query_capabilities()
    assert line.endswith("\n"), "Message must end with a newline for line-oriented protocol"
    payload = parse_json_line(line.strip("\n"))
    assert payload["id"] == QUERY_CMD_ID
    assert payload["command"] == COMMAND_QUERY_CAPABILITIES


def test_parse_json_line_roundtrip() -> None:
    original = {"ack_id": 1, "status": "ok", "capabilities": {"has_thermal": False}}
    text = str(original).replace("'", '"')  # naive JSON from dict
    parsed = parse_json_line(text)
    assert parsed == original
