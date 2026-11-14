from __future__ import annotations

import json
import sqlite3
import time
from pathlib import Path
from typing import Any


class MetricQueue:
    def __init__(self, database_path: Path, limit: int = 10_000) -> None:
        self.database_path = database_path
        self.limit = limit
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=5)
        connection.row_factory = sqlite3.Row
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS metric_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    payload TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at REAL NOT NULL DEFAULT 0
                )
                """
            )

    def enqueue(self, payload: dict[str, Any]) -> None:
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO metric_queue(payload, created_at) VALUES (?, ?)",
                (json.dumps(payload, ensure_ascii=False, separators=(",", ":")), time.time()),
            )
            overflow = connection.execute(
                "SELECT MAX(COUNT(*) - ?, 0) FROM metric_queue", (self.limit,)
            ).fetchone()[0]
            if overflow:
                connection.execute(
                    "DELETE FROM metric_queue WHERE id IN "
                    "(SELECT id FROM metric_queue ORDER BY id LIMIT ?)",
                    (overflow,),
                )

    def due(self, limit: int = 20, now: float | None = None) -> list[tuple[int, dict[str, Any]]]:
        timestamp = time.time() if now is None else now
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT id, payload FROM metric_queue WHERE next_attempt_at <= ? "
                "ORDER BY id LIMIT ?",
                (timestamp, limit),
            ).fetchall()
        return [(row["id"], json.loads(row["payload"])) for row in rows]

    def acknowledge(self, item_id: int) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM metric_queue WHERE id = ?", (item_id,))

    def postpone(self, item_id: int) -> None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT attempt_count FROM metric_queue WHERE id = ?", (item_id,)
            ).fetchone()
            if row is None:
                return
            attempts = row[0] + 1
            delay = min(2 ** min(attempts, 6), 60)
            connection.execute(
                "UPDATE metric_queue SET attempt_count = ?, next_attempt_at = ? WHERE id = ?",
                (attempts, time.time() + delay, item_id),
            )

    def count(self) -> int:
        with self._connect() as connection:
            return int(connection.execute("SELECT COUNT(*) FROM metric_queue").fetchone()[0])
