from __future__ import annotations

import base64
import json
from typing import Dict

from pc_controller.src.network.protocol import (
    encode_frame,
    decode_frames,
    build_v1_cmd,
    build_v1_ack,
    build_v1_error,
    build_v1_preview_frame,
    compute_backoff_schedule,
)


def test_encode_decode_single_frame_roundtrip() -> None:
    msg: Dict[str, object] = {"v": 1, "id": 123, "type": "cmd", "command": "query_capabilities"}
    framed = encode_frame(msg)
    # prefix should be ascii length then newline
    nl = framed.find(b"\n")
    assert nl > 0 and framed[:nl].isdigit()
    length = int(framed[:nl])
    assert length == len(framed[nl + 1 :])
    res = decode_frames(framed)
    assert res.remainder == b""
    assert res.messages == [msg]


def test_decode_multiple_frames_and_partial_buffer() -> None:
    a = {"v": 1, "id": 1, "type": "cmd", "command": "a"}
    b = {"v": 1, "id": 2, "type": "cmd", "command": "b"}
    stream = encode_frame(a) + encode_frame(b)
    # feed partial
    res = decode_frames(stream[:10])
    assert res.messages == []  # not enough to decode first
    # feed full
    res = decode_frames(stream)
    assert res.messages == [a, b]
    assert res.remainder == b""


def test_builders_ack_error_preview() -> None:
    ack = build_v1_ack(ack_id=42, status="ok")
    assert ack["v"] == 1 and ack["type"] == "ack" and ack["ack_id"] == 42

    err = build_v1_error(ack_id=42, code="E_BAD_PARAM", message="bad")
    assert err == {"v": 1, "ack_id": 42, "type": "error", "code": "E_BAD_PARAM", "message": "bad"}

    b64 = base64.b64encode(b"jpeg").decode("ascii")
    ev = build_v1_preview_frame("Pixel 7", b64, 123456789)
    assert ev["v"] == 1 and ev["type"] == "event" and ev["name"] == "preview_frame"


def test_backoff_schedule() -> None:
    sched = compute_backoff_schedule(100, 4)
    assert sched == [100, 200, 400, 800]
