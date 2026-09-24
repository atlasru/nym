from pathlib import Path

import httpx
import pytest

from nym.checker import UsernameChecker
from nym.engine import ScanEngine
from nym.models import CheckResult, CheckStatus
from nym.scheduler import RateScheduler
from nym.storage import Storage


@pytest.mark.asyncio
async def test_engine_pipeline(tmp_path: Path) -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        username = request.content.decode()
        return httpx.Response(200, json={"taken": "free" not in username})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        checker = UsernameChecker(client, endpoint="https://example.test/check")
        async with Storage(\n            tmp_path / "nym.db",\n            available_file=tmp_path / "available.txt",\n        ) as storage:
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
    assert results[0].status is CheckStatus.AVAILABLE
