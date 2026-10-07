import asyncio
import itertools
from pathlib import Path

import httpx
import pytest

from nym.checker import UsernameChecker
from nym.engine import ScanEngine
from nym.models import CheckResult, CheckStatus
from nym.proxy import ProxyEndpoint, ProxyPool
from nym.scheduler import RateScheduler
from nym.storage import Storage


@pytest.mark.asyncio
async def test_engine_pipeline(tmp_path: Path) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        username = request.content.decode()
        return httpx.Response(200, json={"taken": "free" not in username})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        checker = UsernameChecker(client, endpoint="https://example.test/check")
        async with Storage(
            tmp_path / "nym.db",
            available_file=tmp_path / "available.txt",
        ) as storage:
            engine = ScanEngine(
                checker,
                storage,
                RateScheduler(0),
                workers=2,
                queue_size=4,
            )
            results = [result async for result in engine.run(["used", "free"])]

    by_name = {result.username: result.status for result in results}
    assert by_name == {"used": CheckStatus.TAKEN, "free": CheckStatus.AVAILABLE}
    assert engine.stats.generated == 2
    assert engine.stats.checked == 2
    assert engine.stats.available == 1


@pytest.mark.asyncio
async def test_engine_skips_previously_checked(tmp_path: Path) -> None:
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={"taken": True})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        checker = UsernameChecker(client, endpoint="https://example.test/check")
        async with Storage(tmp_path / "nym.db") as storage:
            await storage.save(CheckResult("seen", CheckStatus.TAKEN))
            engine = ScanEngine(checker, storage, RateScheduler(0), workers=1, queue_size=2)
            results = [result async for result in engine.run(["seen", "fresh"])]

    assert [result.username for result in results] == ["fresh"]
    assert calls == 1


@pytest.mark.asyncio
async def test_rate_limit_retries_then_succeeds(tmp_path: Path) -> None:
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(429, headers={"retry-after": "0"})
        return httpx.Response(200, json={"taken": False})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        checker = UsernameChecker(client, endpoint="https://example.test/check")
        async with Storage(tmp_path / "nym.db") as storage:
            engine = ScanEngine(
                checker,
                storage,
                RateScheduler(0),
                workers=1,
                queue_size=2,
                max_rate_limit_retries=1,
            )
            results = [result async for result in engine.run(["free"])]

    assert calls == 2
    assert engine.rate_limit_events == 1
    assert results[0].status is CheckStatus.AVAILABLE


@pytest.mark.asyncio
async def test_long_rate_limit_halts_without_worker_burst(tmp_path: Path) -> None:
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(429, headers={"retry-after": "120"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        checker = UsernameChecker(client, endpoint="https://example.test/check")
        async with Storage(tmp_path / "nym.db") as storage:
            engine = ScanEngine(
                checker,
                storage,
                RateScheduler(0),
                workers=4,
                queue_size=8,
                long_rate_limit_threshold=60,
            )
            results = [
                result
                async for result in engine.run(["one", "two", "three", "four"])
            ]

    assert calls == 1
    assert len(results) == 1
    assert results[0].status is CheckStatus.RATE_LIMITED
    assert engine.rate_limit_events == 1
    assert engine.rate_limit_blocked is True
    assert engine.rate_limit_remaining > 100
    assert engine.stats.checked == 1
    assert engine.stats.rate_limited == 1


@pytest.mark.asyncio
async def test_stop_unblocks_full_queue(tmp_path: Path) -> None:
    class SlowChecker:
        async def check(self, username: str) -> CheckResult:
            await asyncio.sleep(5)
            return CheckResult(username, CheckStatus.TAKEN)

    async with Storage(tmp_path / "nym.db") as storage:
        engine = ScanEngine(
            SlowChecker(),  # type: ignore[arg-type]
            storage,
            RateScheduler(0),
            workers=1,
            queue_size=1,
        )

        async def consume() -> None:
            names = (f"u{number}" for number in itertools.count())
            async for _ in engine.run(names):
                pass

        task = asyncio.create_task(consume())
        await asyncio.sleep(0.05)
        engine.stop()
        await asyncio.wait_for(task, timeout=1.0)


@pytest.mark.asyncio
async def test_proxy_rate_limit_rotates_without_global_halt(tmp_path: Path) -> None:
    first_calls = 0
    second_calls = 0

    async def first_handler(request: httpx.Request) -> httpx.Response:
        nonlocal first_calls
        first_calls += 1
        return httpx.Response(429, headers={"retry-after": "120"}, json={})

    async def second_handler(request: httpx.Request) -> httpx.Response:
        nonlocal second_calls
        second_calls += 1
        return httpx.Response(200, json={"taken": False})

    first = ProxyEndpoint("http://127.0.0.1:8001", "proxy-1")
    second = ProxyEndpoint("http://127.0.0.1:8002", "proxy-2")
    first.client = httpx.AsyncClient(transport=httpx.MockTransport(first_handler))
    second.client = httpx.AsyncClient(transport=httpx.MockTransport(second_handler))
    pool = ProxyPool([first, second])

    try:
        async with httpx.AsyncClient() as client:
            checker = UsernameChecker(
                client,
                endpoint="https://example.test/check",
                proxy_pool=pool,
            )
            async with Storage(tmp_path / "nym.db") as storage:
                engine = ScanEngine(
                    checker,
                    storage,
                    RateScheduler(0),
                    workers=1,
                    queue_size=2,
                    long_rate_limit_threshold=60,
                )
                results = [result async for result in engine.run(["free"])]
    finally:
        await pool.close()

    assert first_calls == 1
    assert second_calls == 1
    assert results[0].status is CheckStatus.AVAILABLE
    assert engine.rate_limit_events == 1
    assert engine.rate_limit_blocked is False
    assert first.rate_limits == 1
    assert first.use_count == 1
    assert second.use_count == 1


@pytest.mark.asyncio
async def test_explicit_global_429_still_halts_proxy_scan(tmp_path: Path) -> None:
    first_calls = 0
    second_calls = 0

    async def first_handler(request: httpx.Request) -> httpx.Response:
        nonlocal first_calls
        first_calls += 1
        return httpx.Response(
            429,
            headers={
                "retry-after": "120",
                "x-ratelimit-global": "true",
            },
            json={"global": True},
        )

    async def second_handler(request: httpx.Request) -> httpx.Response:
        nonlocal second_calls
        second_calls += 1
        return httpx.Response(200, json={"taken": False})

    first = ProxyEndpoint("http://127.0.0.1:8001", "proxy-1")
    second = ProxyEndpoint("http://127.0.0.1:8002", "proxy-2")
    first.client = httpx.AsyncClient(transport=httpx.MockTransport(first_handler))
    second.client = httpx.AsyncClient(transport=httpx.MockTransport(second_handler))
    pool = ProxyPool([first, second])

    try:
        async with httpx.AsyncClient() as client:
            checker = UsernameChecker(
                client,
                endpoint="https://example.test/check",
                proxy_pool=pool,
            )
            async with Storage(tmp_path / "nym.db") as storage:
                engine = ScanEngine(
                    checker,
                    storage,
                    RateScheduler(0),
                    workers=1,
                    queue_size=2,
                    long_rate_limit_threshold=60,
                )
                results = [result async for result in engine.run(["name"])]
    finally:
        await pool.close()

    assert first_calls == 1
    assert second_calls == 0
    assert results[0].status is CheckStatus.RATE_LIMITED
    assert results[0].rate_limit_global is True
    assert engine.rate_limit_blocked is True
