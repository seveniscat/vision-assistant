"""任务表 DAO。任务状态持久化在 SQLite —— 进程重启后已完成结果仍可查，在途任务标记为被打断。"""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from typing import Any

from ..db import Database

_SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks (
    id            TEXT PRIMARY KEY,
    recipe        TEXT NOT NULL,
    status        TEXT NOT NULL,             -- pending | running | done | error
    params_json   TEXT NOT NULL,
    steps_json    TEXT,
    result_json   TEXT,
    error         TEXT,
    callback_url  TEXT,
    callback_attempts INTEGER NOT NULL DEFAULT 0,
    callback_last_code TEXT,
    callback_last_at   TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    finished_at   TEXT
);
CREATE INDEX IF NOT EXISTS idx_tasks_created ON tasks(created_at DESC);
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class TaskDao:
    def __init__(self, db: Database):
        self._db = db
        with db._lock:  # noqa: SLF001  # 建表复用连接锁
            db._conn.executescript(_SCHEMA)  # noqa: SLF001
            db._conn.commit()

    def create(self, task_id: str, recipe: str, params: dict, callback_url: str | None) -> None:
        now = _now()
        self._db.execute(
            "INSERT INTO tasks (id, recipe, status, params_json, callback_url, created_at, updated_at)"
            " VALUES (?, ?, 'pending', ?, ?, ?, ?)",
            (task_id, recipe, json.dumps(params, ensure_ascii=False), callback_url, now, now),
        )

    def get(self, task_id: str) -> sqlite3.Row | None:
        return self._db.query_one("SELECT * FROM tasks WHERE id = ?", (task_id,))

    def list_recent(self, limit: int = 50) -> list[sqlite3.Row]:
        return self._db.query_all("SELECT * FROM tasks ORDER BY created_at DESC LIMIT ?", (limit,))

    def mark_running(self, task_id: str) -> None:
        self._db.execute("UPDATE tasks SET status = 'running', updated_at = ? WHERE id = ?", (_now(), task_id))

    def finish(
        self,
        task_id: str,
        status: str,
        *,
        result: dict | None = None,
        error: dict | None = None,
        steps: list[dict] | None = None,
    ) -> None:
        self._db.execute(
            "UPDATE tasks SET status = ?, result_json = ?, error = ?, steps_json = ?,"
            " finished_at = ?, updated_at = ? WHERE id = ?",
            (
                status,
                json.dumps(result, ensure_ascii=False) if result is not None else None,
                json.dumps(error, ensure_ascii=False) if error is not None else None,
                json.dumps(steps, ensure_ascii=False) if steps is not None else None,
                _now(), _now(), task_id,
            ),
        )

    def update_steps(self, task_id: str, steps: list[dict]) -> None:
        self._db.execute(
            "UPDATE tasks SET steps_json = ?, updated_at = ? WHERE id = ?",
            (json.dumps(steps, ensure_ascii=False), _now(), task_id),
        )

    def webhook_progress(self, task_id: str, attempts: int, last_code: str | None) -> None:
        self._db.execute(
            "UPDATE tasks SET callback_attempts = ?, callback_last_code = ?, callback_last_at = ?, updated_at = ?"
            " WHERE id = ?",
            (attempts, last_code, _now(), _now(), task_id),
        )

    def recover_interrupted(self) -> int:
        """启动时把上次进程未跑完的任务标记为失败（结果可解释，避免无限 pending）。"""
        with self._db._lock:  # noqa: SLF001
            cur = self._db._conn.execute(  # noqa: SLF001
                "UPDATE tasks SET status = 'error',"
                " error = json_object('code', 'INTERRUPTED', 'message', '服务重启，任务中断'),"
                " finished_at = ?, updated_at = ? WHERE status IN ('pending', 'running')",
                (_now(), _now()),
            )
            self._db._conn.commit()  # noqa: SLF001
            return cur.rowcount


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    def parse(raw: str | None) -> Any:
        return json.loads(raw) if raw else None

    return {
        "task_id": row["id"],
        "recipe": row["recipe"],
        "status": row["status"],
        "params": parse(row["params_json"]),
        "steps": parse(row["steps_json"]) or [],
        "result": parse(row["result_json"]),
        "error": parse(row["error"]),
        "callback": {
            "url": row["callback_url"],
            "attempts": row["callback_attempts"],
            "last_code": row["callback_last_code"],
            "last_at": row["callback_last_at"],
        },
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "finished_at": row["finished_at"],
    }
