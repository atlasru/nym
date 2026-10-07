from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from urllib.parse import quote, urlsplit, urlunsplit

import httpx


class ProxyState(StrEnum):
    READY = "ready"
    COOLDOWN = "cooldown"
    DEGRADED = "degraded"
    DEAD = "dead"


class ProxyUnavailableError(RuntimeError):
    pass


@dataclass(slots=True)
class ProxyEndpoint:
    url: str
    display: str
    state: ProxyState = ProxyState.READY
    latency_ms: float | None = None
    successes: int = 0
    failures: int = 0
    rate_limits: int = 0
    consecutive_failures: int = 0
    cooldown_until: float = 0.0
    use_count: int = 0
    in_flight: int = 0
    last_error: str | None = None
    client: httpx.AsyncClient | None = field(default=None, repr=False)

    @property
    def ready(self) -> bool:
        if self.state is ProxyState.DEAD:
            return False
        return time.monotonic() >= self.cooldown_until


class ProxyPool:
    def __init__(
        self,
        endpoints: list[ProxyEndpoint],
        *,
        timeout: float = 10.0,
        cooldown_seconds: float = 30.0,
        dead_after_failures: int = 6,
        fallback_direct: bool = False,
    ) -> None:
        if cooldown_seconds < 0:
            raise ValueError("cooldown_seconds must be >= 0")
        if dead_after_failures < 1:
            raise ValueError("dead_after_failures must be >= 1")
        self.endpoints = endpoints
        self.timeout = timeout
        self.cooldown_seconds = cooldown_seconds
        self.dead_after_failures = dead_after_failures
        self.fallback_direct = fallback_direct
        self._lock = asyncio.Lock()
        self._available = asyncio.Condition(self._lock)
        self._opened = False

    @classmethod
    def from_file(
        cls,
        path: Path,
        *,
        timeout: float = 10.0,
        cooldown_seconds: float = 30.0,
        dead_after_failures: int = 6,
        fallback_direct: bool = False,
    ) -> ProxyPool:
        endpoints: list[ProxyEndpoint] = []
        if path.exists():
            for raw in path.read_text(encoding="utf-8").splitlines():
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                try:
                    url = normalize_proxy_url(line)
                except ValueError:
                    continue
                endpoints.append(ProxyEndpoint(url=url, display=redact_proxy_url(url)))
        return cls(
            endpoints,
            timeout=timeout,
            cooldown_seconds=cooldown_seconds,
            dead_after_failures=dead_after_failures,
            fallback_direct=fallback_direct,
        )

    async def open(self) -> None:
        if self._opened:
            return
        for endpoint in self.endpoints:
            endpoint.client = httpx.AsyncClient(proxy=endpoint.url, timeout=self.timeout)
        self._opened = True

    async def close(self) -> None:
        clients = [endpoint.client for endpoint in self.endpoints if endpoint.client is not None]
        await asyncio.gather(*(client.aclose() for client in clients), return_exceptions=True)
        for endpoint in self.endpoints:
            endpoint.client = None
            endpoint.in_flight = 0
        self._opened = False
        async with self._available:
            self._available.notify_all()

    async def acquire(self) -> ProxyEndpoint | None:
        while True:
            timeout: float | None = None
            async with self._available:
                now = time.monotonic()
                candidates: list[ProxyEndpoint] = []
                for endpoint in self.endpoints:
                    if endpoint.state is ProxyState.DEAD:
                        continue
                    if endpoint.in_flight:
                        continue
                    if endpoint.cooldown_until > now:
                        continue
                    if endpoint.state is ProxyState.COOLDOWN:
                        endpoint.state = ProxyState.DEGRADED
                    candidates.append(endpoint)

                if candidates:
                    endpoint = min(
                        candidates,
                        key=lambda item: (
                            item.consecutive_failures,
                            item.use_count,
                            item.latency_ms
                            if item.latency_ms is not None
                            else float("inf"),
                        ),
                    )
                    endpoint.use_count += 1
                    endpoint.in_flight += 1
                    return endpoint

                live = [
                    endpoint
                    for endpoint in self.endpoints
                    if endpoint.state is not ProxyState.DEAD
                ]
                if not live:
                    if self.fallback_direct:
                        return None
                    raise ProxyUnavailableError("no healthy proxy is currently available")

                if self.fallback_direct:
                    return None

                cooling = [
                    max(0.0, endpoint.cooldown_until - now)
                    for endpoint in live
                    if not endpoint.in_flight and endpoint.cooldown_until > now
                ]
                if cooling:
                    timeout = min(cooling)

                try:
                    if timeout is None:
                        await self._available.wait()
                    else:
                        await asyncio.wait_for(self._available.wait(), timeout=timeout)
                except TimeoutError:
                    pass

    async def release(self, endpoint: ProxyEndpoint) -> None:
        async with self._available:
            endpoint.in_flight = max(0, endpoint.in_flight - 1)
            self._available.notify_all()

    async def next_available_delay(self) -> float | None:
        async with self._lock:
            now = time.monotonic()
            live = [
                endpoint
                for endpoint in self.endpoints
                if endpoint.state is not ProxyState.DEAD
            ]
            if not live:
                return 0.0 if self.fallback_direct else None

            if self.fallback_direct:
                return 0.0

            for endpoint in live:
                if not endpoint.in_flight and endpoint.cooldown_until <= now:
                    return 0.0

            # An in-flight proxy may become usable as soon as its request
            # completes, so do not treat the whole pool as cooling down yet.
            if any(endpoint.in_flight for endpoint in live):
                return 0.0

            delays = [
                max(0.0, endpoint.cooldown_until - now)
                for endpoint in live
                if endpoint.cooldown_until > now
            ]
            return min(delays) if delays else 0.0

    async def report_response(
        self,
        endpoint: ProxyEndpoint,
        status_code: int,
        *,
        latency_ms: float,
        retry_after: float | None = None,
    ) -> None:
        async with self._available:
            endpoint.latency_ms = latency_ms
            endpoint.last_error = None
            if status_code == 429:
                endpoint.rate_limits += 1
                endpoint.state = ProxyState.COOLDOWN
                cooldown = retry_after if retry_after is not None else self.cooldown_seconds
                endpoint.cooldown_until = time.monotonic() + max(0.0, cooldown)
                self._available.notify_all()
                return

            if status_code >= 500:
                self._mark_failure(endpoint, f"HTTP {status_code}")
                self._available.notify_all()
                return

            endpoint.successes += 1
            endpoint.consecutive_failures = 0
            endpoint.cooldown_until = 0.0
            endpoint.state = ProxyState.READY
            self._available.notify_all()

    async def report_error(self, endpoint: ProxyEndpoint, error: BaseException) -> None:
        async with self._available:
            self._mark_failure(endpoint, f"{type(error).__name__}: {error}")
            self._available.notify_all()

    async def health_check_all(
        self,
        *,
        url: str = "https://discord.com/api/v9/gateway",
        concurrency: int = 8,
        request_timeout: float | None = None,
        **options: float,
    ) -> list[ProxyEndpoint]:
        legacy_timeout = options.pop("timeout", None)
        if options:
            unexpected = ", ".join(sorted(options))
            raise TypeError(f"unexpected health check options: {unexpected}")
        if request_timeout is None:
            request_timeout = legacy_timeout

        if not self._opened:
            await self.open()
        semaphore = asyncio.Semaphore(max(1, concurrency))
        effective_timeout = self.timeout if request_timeout is None else request_timeout

        async def check(endpoint: ProxyEndpoint) -> None:
            if endpoint.client is None:
                return
            async with semaphore:
                started = time.perf_counter()
                try:
                    response = await endpoint.client.get(
                        url,
                        timeout=effective_timeout,
                    )
                except httpx.RequestError as exc:
                    await self.report_error(endpoint, exc)
                    return

                latency_ms = (time.perf_counter() - started) * 1000
                endpoint.latency_ms = latency_ms

                if response.status_code != 200:
                    await self.report_error(
                        endpoint,
                        RuntimeError(f"health check HTTP {response.status_code}"),
                    )
                    return

                await self.report_response(
                    endpoint,
                    response.status_code,
                    latency_ms=latency_ms,
                )

        await asyncio.gather(*(check(endpoint) for endpoint in self.endpoints))
        return self.endpoints

    def _mark_failure(self, endpoint: ProxyEndpoint, error: str) -> None:
        endpoint.failures += 1
        endpoint.consecutive_failures += 1
        endpoint.last_error = error
        if endpoint.consecutive_failures >= self.dead_after_failures:
            endpoint.state = ProxyState.DEAD
            endpoint.cooldown_until = 0.0
            return
        endpoint.state = ProxyState.DEGRADED
        endpoint.cooldown_until = time.monotonic() + self.cooldown_seconds

    async def __aenter__(self) -> ProxyPool:
        await self.open()
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.close()


def normalize_proxy_url(raw: str) -> str:
    value = raw.strip()
    if not value:
        raise ValueError("proxy value is empty")

    if "://" not in value and "@" not in value and value.count(":") == 3:
        host, port, username, password = value.split(":", 3)
        value = (
            f"http://{quote(username, safe='')}:{quote(password, safe='')}@{host}:{port}"
        )
    elif "://" not in value:
        value = f"http://{value}"

    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https", "socks5"}:
        raise ValueError(f"unsupported proxy scheme: {parsed.scheme}")
    if parsed.hostname is None:
        raise ValueError("proxy host is missing")
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("invalid proxy port") from exc
    if port is None or not 1 <= port <= 65535:
        raise ValueError("proxy port must be between 1 and 65535")
    return value


def redact_proxy_url(url: str) -> str:
    parsed = urlsplit(url)
    host = parsed.hostname or "?"
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    port = f":{parsed.port}" if parsed.port is not None else ""
    auth = "***:***@" if parsed.username is not None else ""
    netloc = f"{auth}{host}{port}"
    return urlunsplit((parsed.scheme, netloc, parsed.path, parsed.query, parsed.fragment))
