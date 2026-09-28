import time

import pytest

from nym.scheduler import RateScheduler


@pytest.mark.asyncio
async def test_scheduler_accepts_zero_interval() -> None:
    scheduler = RateScheduler(0.0)
    await scheduler.wait()
    await scheduler.wait()


@pytest.mark.asyncio
async def test_scheduler_defer_pushes_global_window_forward() -> None:
    scheduler = RateScheduler(0.0)
    await scheduler.defer(1.0)
    assert scheduler.next_at > time.monotonic()


def test_scheduler_rejects_negative_values() -> None:
    with pytest.raises(ValueError):
        RateScheduler(-1)
    with pytest.raises(ValueError):
        RateScheduler(0.1, jitter=-1)
