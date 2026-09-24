import httpx
import pytest

from nym.checker import UsernameChecker
from nym.models import CheckStatus


@pytest.mark.asyncio
async def test_available() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        return httpx.Response(200, json={"taken": False})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await UsernameChecker(client, endpoint="https://example.test/check").check("free")

    assert result.status is CheckStatus.AVAILABLE
    assert result.http_status == 200


@pytest.mark.asyncio
async def test_taken() -> None:
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"taken": True}))
    async with httpx.AsyncClient(transport=transport) as client:
        result = await UsernameChecker(client, endpoint="https://example.test/check").check("used")

    assert result.status is CheckStatus.TAKEN


@pytest.mark.asyncio
async def test_rate_limit_retry_after_header() -> None:
    transport = httpx.MockTransport(
        lambda request: httpx.Response(429, headers={"retry-after": "1.25"}, json={})
    )
    async with httpx.AsyncClient(transport=transport) as client:
        result = await UsernameChecker(client, endpoint="https://example.test/check").check("name")

    assert result.status is CheckStatus.RATE_LIMITED
    assert result.retry_after == 1.25


@pytest.mark.asyncio
async def test_invalid_payload_is_unknown() -> None:
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={"ok": True}))
    async with httpx.AsyncClient(transport=transport) as client:
        result = await UsernameChecker(client, endpoint="https://example.test/check").check("name")

    assert result.status is CheckStatus.UNKNOWN
