from __future__ import annotations

import time
from collections.abc import Mapping
from typing import Any

import httpx

from .models import CheckResult, CheckStatus
from .proxy import ProxyEndpoint, ProxyPool, ProxyUnavailableError

DEFAULT_ENDPOINT = "https://discord.com/api/v9/unique-username/username-attempt-unauthed"


class UsernameChecker:
    def __init__(
        self,
        client: httpx.AsyncClient,
        *,
        endpoint: str = DEFAULT_ENDPOINT,
        timeout: float = 10.0,
        proxy_pool: ProxyPool | None = None,
    ) -> None:
        self.client = client
        self.endpoint = endpoint
        self.timeout = timeout
        self.proxy_pool = proxy_pool

    async def check(self, username: str) -> CheckResult:
        proxy: ProxyEndpoint | None = None
        request_client = self.client
        if self.proxy_pool is not None and self.proxy_pool.endpoints:
            try:
                proxy = await self.proxy_pool.acquire()
            except ProxyUnavailableError as exc:
                return CheckResult(
                    username=username,
                    status=CheckStatus.NETWORK_ERROR,
                    error=str(exc),
                )
            if proxy is not None:
                if proxy.client is None:
                    await self.proxy_pool.release(proxy)
                    return CheckResult(
                        username=username,
                        status=CheckStatus.NETWORK_ERROR,
                        error=f"{proxy.display}: proxy client is not open",
                    )
                request_client = proxy.client

        try:
            started = time.perf_counter()
            try:
                response = await request_client.post(
                    self.endpoint,
                    json={"username": username},
                    timeout=self.timeout,
                )
            except httpx.RequestError as exc:
                if proxy is not None and self.proxy_pool is not None:
                    await self.proxy_pool.report_error(proxy, exc)
                prefix = f"{proxy.display}: " if proxy is not None else ""
                return CheckResult(
                    username=username,
                    status=CheckStatus.NETWORK_ERROR,
                    error=f"{prefix}{type(exc).__name__}: {exc}",
                    proxied=proxy is not None,
                )

            latency_ms = (time.perf_counter() - started) * 1000
            retry_after = (
                self._retry_after(response) if response.status_code == 429 else None
            )
            if proxy is not None and self.proxy_pool is not None:
                await self.proxy_pool.report_response(
                    proxy,
                    response.status_code,
                    latency_ms=latency_ms,
                    retry_after=retry_after,
                )

            if response.status_code == 429:
                return CheckResult(
                    username=username,
                    status=CheckStatus.RATE_LIMITED,
                    http_status=response.status_code,
                    retry_after=retry_after,
                    error=self._rate_limit_detail(response, proxy),
                    proxied=proxy is not None,
                    rate_limit_global=self._is_global_rate_limit(response),
                )

            if response.status_code in {400, 422}:
                return CheckResult(
                    username=username,
                    status=CheckStatus.INVALID,
                    http_status=response.status_code,
                    proxied=proxy is not None,
                )

            if not 200 <= response.status_code < 300:
                body = response.text[:256]
                prefix = f"{proxy.display}: " if proxy is not None else ""
                return CheckResult(
                    username=username,
                    status=CheckStatus.UNKNOWN,
                    http_status=response.status_code,
                    error=f"{prefix}HTTP {response.status_code}: {body}",
                    proxied=proxy is not None,
                )

            try:
                payload: Mapping[str, Any] = response.json()
            except ValueError as exc:
                return CheckResult(
                    username=username,
                    status=CheckStatus.UNKNOWN,
                    http_status=response.status_code,
                    error=f"invalid JSON: {exc}",
                    proxied=proxy is not None,
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
                proxied=proxy is not None,
            )
        finally:
            if proxy is not None and self.proxy_pool is not None:
                await self.proxy_pool.release(proxy)

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

    @staticmethod
    def _is_global_rate_limit(response: httpx.Response) -> bool:
        global_header = response.headers.get("x-ratelimit-global")
        if global_header and global_header.lower() == "true":
            return True

        try:
            payload = response.json()
        except ValueError:
            return False
        return payload.get("global") is True

    @classmethod
    def _rate_limit_detail(
        cls,
        response: httpx.Response,
        proxy: ProxyEndpoint | None,
    ) -> str | None:
        details: list[str] = []
        if proxy is not None:
            details.append(proxy.display)

        scope = response.headers.get("x-ratelimit-scope")
        if scope:
            details.append(f"scope={scope}")

        if cls._is_global_rate_limit(response):
            details.append("global=true")

        return " · ".join(details) or None
