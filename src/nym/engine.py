from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator, Iterable
from contextlib import suppress

from .checker import UsernameChecker
from .models import CheckResult, CheckStatus, ScanStats
from .scheduler import RateScheduler
from .storage import Storage


class _HaltScan(RuntimeError):
    """Internal signal used to stop workers without emitting fake results."""


class ScanEngine:
    def __init__(
        self,
        checker: UsernameChecker,
        storage: Storage,
        scheduler: RateScheduler,
        *,
        workers: int = 4,
        queue_size: int = 512,
        retry_fallback: float = 1.0,
        max_rate_limit_retries: int = 1,
        long_rate_limit_threshold: float = 60.0,
    ) -> None:
        if workers < 1:
            raise ValueError("workers must be >= 1")
        if queue_size < workers:
            raise ValueError("queue_size must be >= workers")
        if max_rate_limit_retries < 0:
            raise ValueError("max_rate_limit_retries must be >= 0")
        if long_rate_limit_threshold < 0:
            raise ValueError("long_rate_limit_threshold must be >= 0")

        self.checker = checker
        self.storage = storage
        self.scheduler = scheduler
        self.workers = workers
        self.queue_size = queue_size
        self.retry_fallback = retry_fallback
        self.max_rate_limit_retries = max_rate_limit_retries
        self.long_rate_limit_threshold = long_rate_limit_threshold
        self.stats = ScanStats()
        self.rate_limit_events = 0
        self.rate_limit_until = 0.0
        self.rate_limit_blocked = False
        self._queue: asyncio.Queue[str | None] = asyncio.Queue(maxsize=queue_size)
        self._results: asyncio.Queue[CheckResult] = asyncio.Queue()
        self._stop = asyncio.Event()
        self._pause = asyncio.Event()
        self._pause.set()
        self._halt_requested = asyncio.Event()
        self._warmup_complete = asyncio.Event()
        self._warmup_lock = asyncio.Lock()

    @property
    def paused(self) -> bool:
        return not self._pause.is_set()

    @property
    def rate_limit_remaining(self) -> float:
        return max(0.0, self.rate_limit_until - time.monotonic())

    def pause(self) -> None:
        if not self._stop.is_set():
            self._pause.clear()

    def resume(self) -> None:
        self._pause.set()

    def stop(self) -> None:
        self._stop.set()
        self._pause.set()
        self._halt_requested.set()

    async def run(self, usernames: Iterable[str]) -> AsyncIterator[CheckResult]:
        producer = asyncio.create_task(self._produce(usernames), name="nym-producer")
        workers = [
            asyncio.create_task(self._worker(index), name=f"nym-worker-{index}")
            for index in range(self.workers)
        ]

        try:
            while True:
                if self._stop.is_set():
                    producer.cancel()
                    for worker in workers:
                        worker.cancel()
                    break

                if producer.done() and all(worker.done() for worker in workers):
                    break

                try:
                    result = await asyncio.wait_for(self._results.get(), timeout=0.1)
                except TimeoutError:
                    continue

                yield result

            while not self._results.empty():
                yield self._results.get_nowait()
        finally:
            self._stop.set()
            self._pause.set()
            self._halt_requested.set()
            producer.cancel()
            for worker in workers:
                worker.cancel()

            with suppress(asyncio.CancelledError):
                await producer
            await asyncio.gather(*workers, return_exceptions=True)

    async def _produce(self, usernames: Iterable[str]) -> None:
        try:
            for username in usernames:
                await self._pause.wait()
                if self._stop.is_set() or self._halt_requested.is_set():
                    return
                if await self.storage.contains(username):
                    continue
                await self._queue.put(username)
                self.stats.generated += 1
        finally:
            if not self._stop.is_set() and not self._halt_requested.is_set():
                for _ in range(self.workers):
                    await self._queue.put(None)

    async def _worker(self, index: int) -> None:
        del index
        while True:
            username = await self._queue.get()
            try:
                if username is None:
                    return

                await self._pause.wait()
                if self._stop.is_set() or self._halt_requested.is_set():
                    return

                try:
                    result = await self._check_with_rate_limit_retry(username)
                except _HaltScan:
                    return

                await self.storage.save(result)
                self.stats.record(result)
                await self._results.put(result)

                if self.rate_limit_blocked:
                    self.stop()
                    return
                if self._stop.is_set():
                    return
            finally:
                self._queue.task_done()

    async def _send_check(self, username: str) -> CheckResult:
        """Send a check while protecting startup from an in-flight 429 burst.

        Until one real Discord response succeeds, only one worker may have a
        request in flight. This prevents four workers from all hitting a fresh
        long cooldown before the first 429 response is observed.
        """
        if self._halt_requested.is_set():
            raise _HaltScan

        if not self._warmup_complete.is_set():
            async with self._warmup_lock:
                if self._halt_requested.is_set():
                    raise _HaltScan
                if not self._warmup_complete.is_set():
                    await self.scheduler.wait()
                    result = await self.checker.check(username)
                    if result.status not in {
                        CheckStatus.RATE_LIMITED,
                        CheckStatus.NETWORK_ERROR,
                    }:
                        self._warmup_complete.set()
                    return result

        await self.scheduler.wait()
        if self._halt_requested.is_set():
            raise _HaltScan
        return await self.checker.check(username)

    async def _check_with_rate_limit_retry(self, username: str) -> CheckResult:
        attempts = 0
        while True:
            await self._pause.wait()
            if self._stop.is_set() or self._halt_requested.is_set():
                raise _HaltScan

            result = await self._send_check(username)
            if result.status is not CheckStatus.RATE_LIMITED:
                return result

            self.rate_limit_events += 1
            delay = (
                result.retry_after
                if result.retry_after is not None
                else self.retry_fallback
            )
            delay = max(0.0, delay)

            # A proxy-scoped 429 only cools down the proxy that received it.
            # Rotate to another leased endpoint instead of stalling every
            # worker through the global scheduler.
            proxy_pool = self.checker.proxy_pool
            if (
                result.proxied
                and not result.rate_limit_global
                and proxy_pool is not None
            ):
                pool_delay = await proxy_pool.next_available_delay()
                if (
                    pool_delay is not None
                    and pool_delay >= self.long_rate_limit_threshold
                ):
                    self.rate_limit_until = max(
                        self.rate_limit_until,
                        time.monotonic() + pool_delay,
                    )
                    self.rate_limit_blocked = True
                    self._halt_requested.set()
                    return result
                continue

            self.rate_limit_until = max(
                self.rate_limit_until,
                time.monotonic() + delay,
            )

            # Direct or explicitly global Discord limits still gate the whole
            # scanner. A long cooldown fails fast instead of looking frozen.
            await self.scheduler.defer(delay)
            if delay >= self.long_rate_limit_threshold:
                self.rate_limit_blocked = True
                self._halt_requested.set()
                return result

            if attempts >= self.max_rate_limit_retries:
                return result

            attempts += 1
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=delay)
                raise _HaltScan
            except TimeoutError:
                continue
