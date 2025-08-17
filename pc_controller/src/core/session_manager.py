"""Session and recording lifecycle manager (FR4).

Responsibilities:
- Create unique session directories and metadata.json.
- Enforce single active session at a time.
- Manage session state transitions: Idle -> Created -> Recording -> Stopped.

This module is independent of the GUI; other components can import and use it.
"""
from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional, Dict, Any

try:
    # Centralized config loader (NFR8)
    from pc_controller.src.config import get as cfg_get  # when running as a script
except Exception:  # pragma: no cover
    try:
        from ..config import get as cfg_get  # when imported via tests (pythonpath set)
    except Exception:  # pragma: no cover
        def cfg_get(key: str, default=None):  # type: ignore
            return default


@dataclass
class SessionMetadata:
    version: int
    session_id: str
    name: str
    created_at_ns: int
    created_at: str
    state: str  # Created | Recording | Stopped
    start_time_ns: Optional[int] = None
    end_time_ns: Optional[int] = None
    duration_ns: Optional[int] = None


class SessionManager:
    def __init__(self, base_dir: Optional[str] = None) -> None:
        # Align default with GUI's current behavior
        self._base_dir = Path(base_dir or (Path.cwd() / "pc_controller_data")).resolve()
        self._active_id: Optional[str] = None
        self._active_dir: Optional[Path] = None
        self._meta: Optional[SessionMetadata] = None

    # Read-only properties
    @property
    def base_dir(self) -> Path:
        return self._base_dir

    @property
    def is_active(self) -> bool:
        return self._active_id is not None and self._meta is not None and self._meta.state != "Stopped"

    @property
    def session_id(self) -> Optional[str]:
        return self._active_id

    @property
    def session_dir(self) -> Optional[Path]:
        return self._active_dir

    @property
    def metadata(self) -> Optional[Dict[str, Any]]:
        return asdict(self._meta) if self._meta else None

    def _ensure_base(self) -> None:
        self._base_dir.mkdir(parents=True, exist_ok=True)

    def _write_metadata(self) -> None:
        assert self._active_dir is not None and self._meta is not None
        p = self._active_dir / "metadata.json"
        with p.open("w", encoding="utf-8") as f:
            json.dump(asdict(self._meta), f, indent=2)

    def create_session(self, name: str) -> str:
        if self.is_active:
            raise RuntimeError("A session is already active; stop it before creating a new one.")
        self._ensure_base()
        # Use timestamp for uniqueness
        ts = time.strftime("%Y%m%d_%H%M%S")
        sid = f"{ts}_{_sanitize(name)}" if name else ts
        sdir = self._base_dir / sid
        sdir.mkdir(parents=True, exist_ok=True)
        created_ns = time.time_ns()
        created_iso = time.strftime("%Y-%m-%dT%H:%M:%S")
        self._meta = SessionMetadata(
            version=1,
            session_id=sid,
            name=name or sid,
            created_at_ns=created_ns,
            created_at=created_iso,
            state="Created",
        )
        self._active_id = sid
        self._active_dir = sdir
        self._write_metadata()
        return sid

    def start_recording(self) -> None:
        if not self._meta or not self._active_dir:
            raise RuntimeError("No session created.")
        if self._meta.state == "Recording":
            return
        self._meta.state = "Recording"
        self._meta.start_time_ns = time.time_ns()
        self._write_metadata()

    def stop_recording(self) -> None:
        if not self._meta or not self._active_dir:
            return
        if self._meta.state == "Stopped":
            return
        self._meta.state = "Stopped"
        end_ns = time.time_ns()
        self._meta.end_time_ns = end_ns
        try:
            if self._meta.start_time_ns is not None:
                dur = max(0, int(end_ns - int(self._meta.start_time_ns)))
                self._meta.duration_ns = int(dur)
        except Exception:
            # Keep duration_ns as None if computation fails
            pass
        self._write_metadata()


def _sanitize(name: str) -> str:
    # Remove path separators and trim spaces
    return "".join(ch for ch in name if ch.isalnum() or ch in ("-", "_", ".")).strip("._-") or "session"
