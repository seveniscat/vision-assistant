"""API 集成测试：鉴权、健康、能力元信息、流水线任务全生命周期、原子 OCR。"""

from __future__ import annotations

import asyncio
import json
import time

import pytest


async def wait_task(client, task_id: str, timeout: float = 5.0) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        task = (await client.get(f"/api/v1/tasks/{task_id}", headers={"X-API-Key": "key1"})).json()
        if task["status"] in ("done", "error"):
            return task
        await asyncio.sleep(0.05)
    pytest.fail(f"任务 {task_id} 在 {timeout}s 内未结束")


DEFAULT_FILES = [("image", ("pack.png", b"\x89PNG-fake", "image/png"))]


async def submit(client, *, data=None, files=DEFAULT_FILES, key="key1", recipe="ip-consistency-full"):
    kwargs = {"files": files} if files else {}
    return await client.post(
        f"/api/v1/recipes/{recipe}/run",
        data=data if data is not None else {"params": json.dumps({"bizno": "test-bizno"})},
        headers={"X-API-Key": key},
        **kwargs,
    )


class TestAuth:
    async def test_health_is_open(self, client):
        resp, _ = client
        r = await resp.get("/api/v1/health")
        assert r.status_code == 200
        body = r.json()
        assert body["service"] == "vision-assistant"
        assert body["auth_required"] is True
        assert body["capabilities"]["ocr"]["ok"] is True
        assert body["capabilities"]["ipcheck"]["ok"] is True

    async def test_business_endpoints_require_key(self, client):
        resp, _ = client
        for headers in ({}, {"X-API-Key": "wrong"}):
            r = await resp.get("/api/v1/capabilities", headers=headers)
            assert r.status_code == 401
            assert r.json()["error"]["code"] == "UNAUTHORIZED"

    async def test_valid_key_passes(self, client):
        resp, _ = client
        r = await resp.get("/api/v1/capabilities", headers={"X-API-Key": "key2"})
        assert r.status_code == 200
        body = r.json()
        assert {p["name"] for p in body["providers"]} == {"ocr", "ipcheck"}
        assert body["recipes"][0]["name"] == "ip-consistency-full"
        assert "params_schema" in body["recipes"][0]


class TestRecipeLifecycle:
    async def test_full_run_with_upload(self, client):
        resp, captured = client
        r = await submit(resp)
        assert r.status_code == 202
        task_id = r.json()["task_id"]
        assert r.json()["task_url"] == f"/api/v1/tasks/{task_id}"

        task = await wait_task(resp, task_id)
        assert task["status"] == "done", task["error"]

        # 步骤全部成功
        assert [s["name"] for s in task["steps"]] == ["ocr.analyze", "ipcheck.identify_auto", "report"]
        assert all(s["status"] == "ok" for s in task["steps"])

        result = task["result"]
        # 假数据含一个 low_confidence 区域 → 需人工复核
        assert result["summary"]["overall"] == "review"
        assert result["summary"]["verdicts"] == {"match": 1, "mismatch": 0, "low_confidence": 1}
        assert result["ip"]["regions"][0]["role_name"] == "闪电熊"

        # 下游收到的是适配后的 ocr_texts：偏移已加（100+10=110），qr/空白被剔除
        ip_content = captured["ip_content"]
        assert captured["ip_query"]["bizno"] == "test-bizno"
        assert b'"x1": 110' in ip_content
        assert b"http://example.com" not in ip_content
        assert "annotated_image_b64" not in result["ocr"]

    async def test_full_run_with_url(self, client):
        resp, captured = client
        r = await submit(resp, data={
            "params": json.dumps({"bizno": "test-bizno", "match_min": 0.7}),
            "image_url": "http://images.test/pack.png",
        }, files=[])
        assert r.status_code == 202
        task = await wait_task(resp, r.json()["task_id"])
        assert task["status"] == "done"
        assert b"images.test" in captured["ocr_content"]

    async def test_missing_image_rejected(self, client):
        resp, _ = client
        r = await submit(resp, files=[])
        assert r.status_code == 400
        assert r.json()["error"]["code"] == "INVALID_ARGUMENT"

    async def test_invalid_params_rejected(self, client):
        resp, _ = client
        r = await submit(resp, data={"params": json.dumps({"bizno": "b", "match_min": 5.0})})
        assert r.status_code == 422

    async def test_bad_params_json_rejected(self, client):
        resp, _ = client
        r = await submit(resp, data={"params": "{not-json"})
        assert r.status_code == 422

    async def test_unknown_recipe_404(self, client):
        resp, _ = client
        r = await submit(resp, recipe="no-such-recipe")
        assert r.status_code == 404

    async def test_task_not_found(self, client):
        resp, _ = client
        r = await resp.get("/api/v1/tasks/nope", headers={"X-API-Key": "key1"})
        assert r.status_code == 404

    async def test_tasks_listed(self, client):
        resp, _ = client
        await submit(resp)
        r = await resp.get("/api/v1/tasks?limit=10", headers={"X-API-Key": "key1"})
        assert r.status_code == 200
        assert len(r.json()["tasks"]) >= 1


class TestAtomicOcr:
    async def test_analyze_passthrough(self, client):
        resp, captured = client
        r = await resp.post(
            "/api/v1/ocr/analyze",
            data={"options": json.dumps({"granularity": "line"})},
            files={"image": ("a.png", b"\x89PNG-fake", "image/png")},
            headers={"X-API-Key": "key1"},
        )
        assert r.status_code == 200
        assert len(r.json()["items"]) == 4
        assert b"name=\"file\"" in captured["ocr_content"]

    async def test_requires_auth(self, client):
        resp, _ = client
        r = await resp.post("/api/v1/ocr/analyze")
        assert r.status_code == 401
