from __future__ import annotations

import asyncio
import sqlite3
from pathlib import Path

from .models import CheckResult, CheckStatus

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;

CREATE TABLE IF NOT EXISTS results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL,
    status TEXT NOT NULL,
    checked_at TEXT NOT NULL,
    http_status INTEGER,
    retry_after REAL,
    error TEXT
);

CREATE INDEX IF NOT EXISTS idx_results_username ON results(username);
CREATE INDEX IF NOT EXISTS idx_results_status ON results(status);

CREATE TABLE IF NOT EXISTS checked_usernames (
    username TEXT PRIMARY KEY,
    last_status TEXT NOT NULL,
    checked_at TEXT NOT NULL
);
"""


class Storage:
    def __init__(
        self,
        database: Path,
        *,
        available_file: Path | None = None,
    ) -> None:
        self.database = database
        self.available_file = available_file
        self._conn: sqlite3.Connection | None = None
        self._lock = asyncio.Lock()

    async def open(self) -> None:
        self.database.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self.database)
        self._conn.executescript(SCHEMA)
        self._conn.commit()

        if self.available_file is not None:
            self.available_file.parent.mkdir(parents=True, exist_ok=True)

    async def close(self) -> None:
        async with self._lock:
            if self._conn is not None:
                self._conn.commit()
                self._conn.close()
                self._conn = None

    async def contains(self, username: str) -> bool:
        async with self._lock:
            conn = self._require_connection()
            row = conn.execute(
                "SELECT 1 FROM checked_usernames WHERE username = ? LIMIT 1",
                (username,),
            ).fetchone()
            return row is not None

    async def save(self, result: CheckResult) -> None:
        async with self._lock:
            conn = self._require_connection()
            conn.execute(
                """
                INSERT INTO results (
                    username, status, checked_at, http_status, retry_after, error
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    result.username,
                    result.status.value,
                    result.checked_at.isoformat(),
                    result.http_status,
                    result.retry_after,
                    result.error,
                ),
            )
            conn.execute(
                """
                INSERT INTO checked_usernames (username, last_status, checked_at)
                VALUES (?, ?, ?)
                ON CONFLICT(username) DO UPDATE SET
                    last_status = excluded.last_status,
                    checked_at = excluded.checked_at
                """,
                (
                    result.username,
                    result.status.value,
                    result.checked_at.isoformat(),
                ),
            )
            conn.commit()

            if result.status is CheckStatus.AVAILABLE and self.available_file is not None:
                with self.available_file.open("a", encoding="utf-8", newline="\n") as handle:
                    handle.write(result.username + "\n")
                    handle.flush()

    def _require_connection(self) -> sqlite3.Connection:
        if self._conn is None:
            raise RuntimeError("storage is not open")
        return self._conn

    async def __aenter__(self) -> Storage:
        await self.open()
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.close()
