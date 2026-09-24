from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum


class CheckStatus(StrEnum):
    AVAILABLE = "available"
    TAKEN = "taken"
    INVALID = "invalid"
    RATE_LIMITED = "rate_limited"
    NETWORK_ERROR = "network_error"
    UNKNOWN = "unknown"


@dataclass(slots=True, frozen=True)
class CheckResult:
    username: str
    status: CheckStatus
    checked_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    http_status: int | None = None
    retry_after: float | None = None
    error: str | None = None


@dataclass(slots=True)
class ScanStats:
    generated: int = 0
    checked: int = 0
    available: int = 0
    taken: int = 0
    invalid: int = 0
    rate_limited: int = 0
    network_errors: int = 0
    unknown: int = 0

    def record(self, result: CheckResult) -> None:
        self.checked += 1
        match result.status:
            case CheckStatus.AVAILABLE:
                self.available += 1
            case CheckStatus.TAKEN:
                self.taken += 1
            case CheckStatus.INVALID:
                self.invalid += 1
            case CheckStatus.RATE_LIMITED:
                self.rate_limited += 1
            case CheckStatus.NETWORK_ERROR:
                self.network_errors += 1
            case CheckStatus.UNKNOWN:
                self.unknown += 1
