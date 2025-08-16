"""Protocol definitions and helpers for PC<->Android communication (Phase 1).

This module defines the message formats for the initial handshake as
specified in docs/4_1_phase.md.

Service Type (mDNS/Zeroconf):
- _gsr-controller._tcp.local.

Initial command from PC to Android:
- {"id": 1, "command": "query_capabilities"}

Expected response from Android:
- {"ack_id": 1, "status": "ok", "capabilities": { ... }}
"""
from __future__ import annotations

from typing import Any, Dict
import json

QUERY_CMD_ID = 1
COMMAND_QUERY_CAPABILITIES = "query_capabilities"


def build_query_capabilities() -> str:
    """Return a JSON line string for the capabilities query.

    The payload matches the Phase 1 spec and ends with a newline suitable
    for line-oriented TCP protocols.
    """
    payload = {"id": QUERY_CMD_ID, "command": COMMAND_QUERY_CAPABILITIES}
    return json.dumps(payload) + "\n"


def parse_json_line(line: str) -> Dict[str, Any]:
    """Parse a single JSON line into a dictionary.

    Raises json.JSONDecodeError if parsing fails.
    """
    return json.loads(line)
