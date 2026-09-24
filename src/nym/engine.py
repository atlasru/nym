from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Iterable
from contextlib import suppress

from .checker import UsernameChecker
from .models import CheckResult, CheckStatus, ScanStats
from .scheduler import RateScheduler
from .storage import Storage


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
        max_rate_limit_retries: int = 3,
    ) -> None:
        if workers < 1:
            raise ValueError("workers must be >= 1")
        if queue_size < workers:
            raise ValueError("queue_size must be >= workers")
        if max_rate_limit_retries < 0:
            raise ValueError("max_rate_limit_retries must be >= 0")

        self.checker = checker
        self.storage = storage
        self.scheduler = scheduler
        self.workers = workers
        self.queue_size = queue_size
        self.retry_fallback = retry_fallback
        self.max_rate_limit_retries = max_rate_limit_retries
        self.stats = ScanStats()
        self._queue: asyncio.Queue[str | None] = asyncio.Queue(maxsize=queue_size)
        self._results: asyncio.Queue[CheckResult] = asyncio.Queue()
        self._stop = asyncio.Event()

    def stop(self) -> None:
        self._stop.set()

    async def run(self, usernames: Iterable[str]) -> AsyncIterator[CheckResult]:
        producer = asyncio.create_task(self._produce(usernames), name="nym-producer")
        workers = [
            asyncio.create_task(self._worker(index), name=f"nym-worker-{index}")
            for index in range(self.workers)
        ]

        try:
            while True:
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
            producer.cancel()
            for worker in workers:
                worker.cancel()

            with suppress(asyncio.CancelledError):
                await producer
            await asyncio.gather(*workers, return_exceptions=True)

    async def _produce(self, usernames: Iterable[str]) -> None:
        try:
            for username in usernames:
                if self._stop.is_set():
                    return
                if await self.storage.contains(username):
                    continue
                await self._queue.put(username)
                self.stats.generated += 1
        finally:
            if not self._stop.is_set():
                for _ in range(self.workers):
                    await self._queue.put(None)

    async def _worker(self, index: int) -> None:
        del index
        while True:
            username = await self._queue.get()
            try:
                if username is None:
                    return
                if self._stop.is_set():
                    return

                result = await self._check_with_rate_limit_retry(username)
                await self.storage.save(result)
                self.stats.record(result)
                await self._results.put(result)
            finally:
                self._queue.task_done()

    async def _check_with_rate_limit_retry(self, username: str) -> CheckResult:
        attempts = 0
        while True:
            await self.scheduler.wait()
            result = await self.checker.check(username)
            if result.status is not CheckStatus.RATE_LIMITED:
                return result
            if attempts >= self.max_rate_limit_retries:
                return result

            attempts += 1
            await asyncio.sleep(
                result.retry_after if result.retry_after is not None else self.retry_fallback
            )
