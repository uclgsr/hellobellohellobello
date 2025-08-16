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
import ast

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

    Primary parser is strict JSON. If that fails (e.g., tests provide
    Python-literal dicts with True/False), fall back to ast.literal_eval.
    """
    try:
        return json.loads(line)
    except Exception:
        # Safe evaluation of Python literals as a fallback for tests
        obj = ast.literal_eval(line)
        if not isinstance(obj, dict):  # type: ignore[unreachable]
            raise ValueError("Parsed object is not a dict")
        return obj  # type: ignore[return-value]
