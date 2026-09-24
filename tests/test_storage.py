from pathlib import Path

import pytest

from nym.models import CheckResult, CheckStatus
from nym.storage import Storage


@pytest.mark.asyncio
async def test_save_and_deduplicate(tmp_path: Path) -> None:
    database = tmp_path / "data" / "nym.db"
    available = tmp_path / "available.txt"

    async with Storage(database, available_file=available) as storage:
        assert not await storage.contains("alpha")
        await storage.save(CheckResult("alpha", CheckStatus.AVAILABLE, http_status=200))
        assert await storage.contains("alpha")

    assert available.read_text(encoding="utf-8") == "alpha\n"


@pytest.mark.asyncio
async def test_transient_result_does_not_poison_dedup(tmp_path: Path) -> None:
    async with Storage(tmp_path / "nym.db") as storage:
        await storage.save(CheckResult("alpha", CheckStatus.RATE_LIMITED, http_status=429))
        assert not await storage.contains("alpha")


@pytest.mark.asyncio
async def test_taken_is_not_written_to_available(tmp_path: Path) -> None:
    available = tmp_path / "available.txt"

    async with Storage(tmp_path / "nym.db", available_file=available) as storage:
        await storage.save(CheckResult("taken", CheckStatus.TAKEN, http_status=200))

    assert not available.exists()
