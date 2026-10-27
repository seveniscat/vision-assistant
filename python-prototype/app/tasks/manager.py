"""任务管理器：提交 → 进程内异步执行 → 状态持久化 → 回调投递（带重试 + 可选 HMAC 签名）。"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any

import httpx

from ..capabilities.base import CapabilityError, CapabilityProvider
from ..config import Settings
from ..orchestration import MediaInput, Recipe, RunContext, get_recipe
from .dao import TaskDao, row_to_dict

logger = logging.getLogger("va.tasks")


class TaskManager:
    def __init__(
        self,
        dao: TaskDao,
        providers: dict[str, CapabilityProvider],
        settings: Settings,
        *,
        webhook_transport: httpx.AsyncBaseTransport | None = None,
    ):
        self._dao = dao
        self._providers = providers
        self._settings = settings
        self._webhook_transport = webhook_transport

    # ── 对外 ──

    def submit(
        self,
        recipe_name: str,
        params: Any,
        media: MediaInput,
        callback_url: str | None = None,
    ) -> dict:
        recipe = get_recipe(recipe_name)
        if recipe is None:
            from ..errors import VaError

            raise VaError("NOT_FOUND", f"未注册的流水线: {recipe_name}", http_status=404)

        task_id = uuid.uuid4().hex
        safe_params = params.model_dump(mode="json")
        if media.data is not None:
            # 不落盘图片字节；只登记元信息
            safe_params["_upload"] = {"filename": media.filename, "bytes": len(media.data)}
        self._dao.create(task_id, recipe_name, safe_params, callback_url)
        asyncio.get_running_loop().create_task(
            self._execute(task_id, recipe, params, media, callback_url)
        )
        return row_to_dict(self._dao.get(task_id))  # type: ignore[arg-type]

    def get(self, task_id: str) -> dict | None:
        row = self._dao.get(task_id)
        return row_to_dict(row) if row else None

    def list_recent(self, limit: int = 50) -> list[dict]:
        return [row_to_dict(row) for row in self._dao.list_recent(limit)]

    def recover_interrupted(self) -> int:
        return self._dao.recover_interrupted()

    # ── 执行 ──

    async def _execute(
        self, task_id: str, recipe: Recipe, params: Any, media: MediaInput, callback_url: str | None
    ) -> None:
        self._dao.mark_running(task_id)
        ctx = RunContext(task_id, self._providers)
        try:
            result = await recipe.run(ctx, params, media)
            self._dao.finish(task_id, "done", result=result, steps=ctx.steps_payload())
        except CapabilityError as exc:
            self._dao.finish(task_id, "error", error=exc.to_dict(), steps=ctx.steps_payload())
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.exception("task %s failed", task_id)
            self._dao.finish(
                task_id, "error",
                error={"code": "INTERNAL", "message": f"流水线执行异常: {exc}"},
                steps=ctx.steps_payload(),
            )
        finally:
            if callback_url:
                asyncio.get_running_loop().create_task(self._deliver_webhook(task_id, callback_url))

    async def _deliver_webhook(self, task_id: str, callback_url: str) -> None:
        row = self._dao.get(task_id)
        if row is None:
            return
        payload = {
            "event": "task.completed" if row["status"] == "done" else "task.failed",
            "task_id": task_id,
            "recipe": row["recipe"],
            "status": row["status"],
            "error": json.loads(row["error"]) if row["error"] else None,
            "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        secret = self._settings.webhook_secret
        if secret:
            headers["X-VA-Signature"] = hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()

        async with httpx.AsyncClient(transport=self._webhook_transport) as client:
            for attempt in range(1, self._settings.task_webhook_max_attempts + 1):
                try:
                    response = await client.post(callback_url, content=body, headers=headers, timeout=10.0)
                    ok = 200 <= response.status_code < 300
                    code = str(response.status_code)
                except httpx.HTTPError as exc:
                    ok, code = False, f"transport:{type(exc).__name__}"
                self._dao.webhook_progress(task_id, attempt, code)
                if ok:
                    return
                logger.warning("webhook %s attempt %d/%d failed: %s", callback_url, attempt,
                               self._settings.task_webhook_max_attempts, code)
                if attempt < self._settings.task_webhook_max_attempts:
                    backoff = self._settings.task_webhook_backoff_seconds * (2 ** (attempt - 1))
                    await asyncio.sleep(backoff)
