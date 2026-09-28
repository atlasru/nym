from __future__ import annotations

import asyncio
import random
import time


class RateScheduler:
    """Global cooperative request scheduler.

    Keeps workers inside a configured request cadence. This is not a rate-limit
    bypass mechanism; Retry-After handling remains the checker's responsibility.
    """

    def __init__(
        self,
        interval: float = 0.1,
        *,
        jitter: float = 0.0,
        seed: int | None = None,
    ) -> None:
        if interval < 0:
            raise ValueError("interval must be >= 0")
        if jitter < 0:
            raise ValueError("jitter must be >= 0")
        self.interval = interval
        self.jitter = jitter
        self._rng = random.Random(seed)
        self._lock = asyncio.Lock()
        self._next_at = 0.0

    @property
    def next_at(self) -> float:
        return self._next_at

    async def wait(self) -> None:
        async with self._lock:
            now = time.monotonic()
            delay = max(0.0, self._next_at - now)
            if delay:
                await asyncio.sleep(delay)

            spacing = self.interval
            if self.jitter:
                spacing += self._rng.uniform(0.0, self.jitter)
            self._next_at = time.monotonic() + spacing

    async def defer(self, delay: float) -> None:
        """Push the global request window forward by ``delay`` seconds.

        Used when the server returns Retry-After so every worker observes the
        same cooldown instead of independently continuing to send requests.
        """
        if delay <= 0:
            return
        async with self._lock:
            self._next_at = max(self._next_at, time.monotonic() + delay)
