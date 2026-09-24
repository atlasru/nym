from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

import httpx

from .models import CheckResult, CheckStatus

DEFAULT_ENDPOINT = "https://discord.com/api/v9/unique-username/username-attempt-unauthed"


class UsernameChecker:
    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        endpoint: str = DEFAULT_ENDPOINT,
        timeout: float = 10.0,
    ) -> None:
        self.client = client
        self.endpoint = endpoint
        self.timeout = timeout

    async def check(self, username: str) -> CheckResult:
        try:
            response = await self.client.post(
                self.endpoint,
                json={"username": username},
                timeout=self.timeout,
            )
        except (httpx.TimeoutException, httpx.NetworkError) as exc:
            return CheckResult(
                username=username,
                status=CheckStatus.NETWORK_ERROR,
                error=f"{type(exc).__name__}: {exc}",
            )

        if response.status_code == 429:
            retry_after = self._retry_after(response)
            return CheckResult(
                username=username,
                status=CheckStatus.RATE_LIMITED,
                http_status=response.status_code,
                retry_after=retry_after,
            )

        if response.status_code in {400, 422}:
            return CheckResult(
                username=username,
                status=CheckStatus.INVALID,
                http_status=response.status_code,
            )

        if not 200 <= response.status_code < 300:
            return CheckResult(
                username=username,
                status=CheckStatus.UNKNOWN,
                http_status=response.status_code,
                error=response.text[:256],
            )

        try:
            payload: Mapping[str, Any] = response.json()
        except ValueError as exc:
            return CheckResult(
                username=username,
                status=CheckStatus.UNKNOWN,
                http_status=response.status_code,
                error=f"invalid JSON: {exc}",
            )

        taken = payload.get("taken")
        if taken is True:
            status = CheckStatus.TAKEN
        elif taken is False:
            status = CheckStatus.AVAILABLE
        else:
            status = CheckStatus.UNKNOWN

        return CheckResult(
            username=username,
            status=status,
            http_status=response.status_code,
        )

    @staticmethod
    def _retry_after(response: httpx.Response) -> float | None:
        header = response.headers.get("retry-after")
        if header is not None:
            try:
                return max(0.0, float(header))
            except ValueError:
                pass

        try:
            payload = response.json()
        except ValueError:
            return None

        raw = payload.get("retry_after")
        try:
            return max(0.0, float(raw))
        except (TypeError, ValueError):
            return None


async def sleep_retry_after(result: CheckResult, *, fallback: float = 1.0) -> None:
    if result.status is CheckStatus.RATE_LIMITED:
        await asyncio.sleep(result.retry_after if result.retry_after is not None else fallback)
