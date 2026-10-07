import asyncio
from pathlib import Path

import pytest

from nym.proxy import ProxyEndpoint, ProxyPool, ProxyState, normalize_proxy_url, redact_proxy_url


def test_normalize_bare_http_proxy() -> None:
    assert normalize_proxy_url("127.0.0.1:8080") == "http://127.0.0.1:8080"


def test_normalize_host_port_user_password() -> None:
    value = normalize_proxy_url("proxy.example:3128:user:pass")
    assert value == "http://user:pass@proxy.example:3128"


def test_redacts_credentials() -> None:
    value = redact_proxy_url("http://user:secret@proxy.example:8080")
    assert value == "http://***:***@proxy.example:8080"
    assert "secret" not in value


def test_invalid_proxy_port() -> None:
    with pytest.raises(ValueError):
        normalize_proxy_url("proxy.example:99999")


def test_pool_loads_valid_lines_only(tmp_path: Path) -> None:
    path = tmp_path / "proxies.txt"
    path.write_text(
        "127.0.0.1:8080\n# comment\nnot-a-proxy\nsocks5://127.0.0.1:1080\n",
        encoding="utf-8",
    )

    pool = ProxyPool.from_file(path)

    assert len(pool.endpoints) == 2


@pytest.mark.asyncio
async def test_pool_leases_distinct_proxies_until_release() -> None:
    first = ProxyEndpoint("http://127.0.0.1:8001", "proxy-1")
    second = ProxyEndpoint("http://127.0.0.1:8002", "proxy-2")
    pool = ProxyPool([first, second])

    leased_first = await pool.acquire()
    leased_second = await pool.acquire()

    assert leased_first is first
    assert leased_second is second
    assert first.in_flight == 1
    assert second.in_flight == 1

    waiter = asyncio.create_task(pool.acquire())
    await asyncio.sleep(0)
    assert not waiter.done()

    await pool.release(first)
    leased_third = await asyncio.wait_for(waiter, timeout=0.2)

    assert leased_third is first
    assert first.in_flight == 1

    await pool.release(first)
    await pool.release(second)


@pytest.mark.asyncio
async def test_pool_skips_rate_limited_proxy_during_cooldown() -> None:
    first = ProxyEndpoint("http://127.0.0.1:8001", "proxy-1")
    second = ProxyEndpoint("http://127.0.0.1:8002", "proxy-2")
    pool = ProxyPool([first, second])

    leased = await pool.acquire()
    assert leased is first

    await pool.report_response(first, 429, latency_ms=10.0, retry_after=120.0)
    await pool.release(first)

    rotated = await pool.acquire()
    assert rotated is second
    assert first.state is ProxyState.COOLDOWN
    assert first.rate_limits == 1

    await pool.release(second)
