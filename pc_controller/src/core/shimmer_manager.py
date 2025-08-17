"""Shimmer sensor management and simulation (FR1).

Provides a SimulatedShimmer with the same public surface as a hypothetical
real Shimmer manager: connect(), start_streaming(callback), stop_streaming(),
disconnect(). When streaming, generates GSR-like sine+noise samples at
shimmer_sampling_rate from config.json.
"""
from __future__ import annotations

import math
import threading
import time
from typing import Callable, Optional

try:
    from ..config import get as cfg_get
except Exception:  # pragma: no cover
    def cfg_get(key: str, default=None):  # type: ignore
        return default


class SimulatedShimmer:
    def __init__(self, sample_rate_hz: Optional[int] = None) -> None:
        self._rate = int(sample_rate_hz or int(cfg_get("shimmer_sampling_rate", 128)))
        if self._rate <= 0:
            self._rate = 128
        self._thread: Optional[threading.Thread] = None
        self._running = threading.Event()
        self._callback: Optional[Callable[[int, float], None]] = None

    # Public API mirroring a real manager
    def connect(self) -> bool:
        return True

    def start_streaming(self, callback: Callable[[int, float], None]) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._callback = callback
        self._running.set()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop_streaming(self) -> None:
        self._running.clear()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=0.5)
        self._thread = None
        self._callback = None

    def disconnect(self) -> None:
        self.stop_streaming()

    # Internal
    def _loop(self) -> None:
        dt = 1.0 / float(self._rate)
        next_t = time.monotonic()
        phase = 0.0
        two_pi = 2.0 * math.pi
        while self._running.is_set():
            now = time.monotonic()
            if now < next_t:
                time.sleep(max(0.0, next_t - now))
                continue
            ts_ns = time.monotonic_ns()
            # Baseline 10 uS, 1.2 Hz sine, small noise
            val = 10.0 + 2.0 * math.sin(phase)
            phase = (phase + two_pi * 1.2 * dt) % two_pi
            cb = self._callback
            if cb:
                try:
                    cb(ts_ns, float(val))
                except Exception:
                    pass
            next_t += dt
