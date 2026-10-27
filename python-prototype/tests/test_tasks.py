"""任务层测试：回调重试/签名、重启恢复。"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import time

from app.db import Database
from app.tasks import TaskDao

from conftest import make_client, webhook_transport


async def wait_until(predicate, timeout: float = 5.0, interval: float = 0.05):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if asyncio.iscoroutine(result):
            result = await result
        if result:
            return
        await asyncio.sleep(interval)


class TestWebhook:
    async def test_webhook_retries_and_succeeds(self, settings):
        settings.task_webhook_backoff_seconds = 0.01
        settings.task_webhook_max_attempts = 3
        captured: dict = {}
        transport = webhook_transport(captured, status_script=(500, 204))

        async with make_client(settings, webhook_transport=transport) as (resp, _):
            r = await resp.post(
                "/api/v1/recipes/ip-consistency-full/run",
                data={"params": json.dumps({"bizno": "b"}), "callback_url": "http://cb/hook"},
                files=[("image", ("p.png", b"x", "image/png"))],
                headers={"X-API-Key": "key1"},
            )
            assert r.status_code == 202
            task_id = r.json()["task_id"]

            async def delivered():
                task = (await resp.get(f"/api/v1/tasks/{task_id}", headers={"X-API-Key": "key1"})).json()
                return task["callback"]["attempts"] >= 2

            await wait_until(delivered)
            task = (await resp.get(f"/api/v1/tasks/{task_id}", headers={"X-API-Key": "key1"})).json()
            assert task["status"] == "done"
            assert task["callback"]["last_code"] == "204"
            assert len(captured["webhooks"]) == 2

            event = json.loads(captured["webhooks"][0]["body"])
            assert event["event"] == "task.completed"
            assert event["task_id"] == task_id
            assert event["status"] == "done"

    async def test_webhook_exhausts_attempts(self, settings):
        settings.task_webhook_backoff_seconds = 0.01
        settings.task_webhook_max_attempts = 3
        captured: dict = {}
        transport = webhook_transport(captured, status_script=(500,))

        async with make_client(settings, webhook_transport=transport) as (resp, _):
            r = await resp.post(
                "/api/v1/recipes/ip-consistency-full/run",
                data={"params": json.dumps({"bizno": "b"}), "callback_url": "http://cb/hook"},
                files=[("image", ("p.png", b"x", "image/png"))],
                headers={"X-API-Key": "key1"},
            )
            task_id = r.json()["task_id"]

            async def exhausted():
                task = (await resp.get(f"/api/v1/tasks/{task_id}", headers={"X-API-Key": "key1"})).json()
                return task["callback"]["attempts"] >= 3

            await wait_until(exhausted)
            task = (await resp.get(f"/api/v1/tasks/{task_id}", headers={"X-API-Key": "key1"})).json()
            assert task["callback"]["last_code"] == "500"
            assert len(captured["webhooks"]) == 3
            # 回调失败不影响任务本身结果
            assert task["status"] == "done"

    async def test_webhook_signature(self, settings):
        settings.task_webhook_backoff_seconds = 0.01
        settings.webhook_secret = "s3cret"
        captured: dict = {}
        transport = webhook_transport(captured, status_script=(204,))

        async with make_client(settings, webhook_transport=transport) as (resp, _):
            await resp.post(
                "/api/v1/recipes/ip-consistency-full/run",
                data={"params": json.dumps({"bizno": "b"}), "callback_url": "http://cb/hook"},
                files=[("image", ("p.png", b"x", "image/png"))],
                headers={"X-API-Key": "key1"},
            )

            async def delivered():
                return len(captured.get("webhooks", [])) >= 1

            await wait_until(delivered)
            hook = captured["webhooks"][0]
            expected = hmac.new(b"s3cret", hook["body"], hashlib.sha256).hexdigest()
            assert hook["headers"].get("x-va-signature") == expected


class TestRestartRecovery:
    def test_interrupted_tasks_marked_error(self, tmp_path):
        db = Database(tmp_path / "va.sqlite3")
        dao = TaskDao(db)
        dao.create("t1", "ip-consistency-full", {"bizno": "b"}, None)
        dao.create("t2", "ip-consistency-full", {"bizno": "b"}, None)
        dao.mark_running("t2")

        assert dao.recover_interrupted() == 2
        for task_id in ("t1", "t2"):
            row = dao.get(task_id)
            assert row is not None
            error = json.loads(row["error"])
            assert error["code"] == "INTERRUPTED"

        # 已完成任务不受影响
        dao.create("t3", "ip-consistency-full", {}, None)
        dao.finish("t3", "done", result={"ok": 1})
        assert dao.recover_interrupted() == 0
        assert dao.get("t3")["status"] == "done"

    def test_second_recovery_is_noop(self, tmp_path):
        db = Database(tmp_path / "va.sqlite3")
        dao = TaskDao(db)
        dao.create("t1", "r", {}, None)
        dao.recover_interrupted()
        assert dao.recover_interrupted() == 0
