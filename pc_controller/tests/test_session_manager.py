from __future__ import annotations

import json
from pathlib import Path

from pc_controller.src.core.session_manager import SessionManager


def test_create_start_stop_session(tmp_path: Path) -> None:
    sm = SessionManager(base_dir=str(tmp_path))
    sid = sm.create_session("test_name")
    assert sm.session_id == sid
    sdir = sm.session_dir
    assert sdir is not None and sdir.exists()
    meta_path = sdir / "metadata.json"
    assert meta_path.exists()
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    assert meta["session_id"] == sid
    assert meta["name"]
    assert meta["state"] == "Created"

    sm.start_recording()
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    assert meta["state"] == "Recording"
    assert isinstance(meta.get("start_time_ns"), int)

    sm.stop_recording()
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    assert meta["state"] == "Stopped"
    assert isinstance(meta.get("end_time_ns"), int)


def test_single_active_enforced(tmp_path: Path) -> None:
    sm = SessionManager(base_dir=str(tmp_path))
    sm.create_session("first")
    threw = False
    try:
        sm.create_session("second")
    except RuntimeError:
        threw = True
    assert threw is True

    sm.stop_recording()  # allowed even if not started; transitions to Stopped
    sid2 = sm.create_session("second")
    assert sm.session_id == sid2
