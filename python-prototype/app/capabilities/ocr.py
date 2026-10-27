"""ocr-agent 适配器（/analyze 核心接口，含大图 202 任务轮询）。"""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

import httpx

from .base import CapabilityError, CapabilityProvider


class OcrProvider(CapabilityProvider):
    name = "ocr"
    operations = {
        "analyze": "OCR 识别文字/艺术字/二维码/条码，返回内容 + 像素坐标框（自动处理大图异步轮询）",
    }

    def __init__(self, *, poll_interval: float = 2.0, poll_timeout: float = 900.0, **kwargs):
        super().__init__(**kwargs)
        self.poll_interval = poll_interval
        self.poll_timeout = poll_timeout

    async def health(self) -> dict:
        try:
            response = await self._call("GET", "/healthz", accepted=(200,), timeout=3.0)
            return {"ok": True, "detail": response.json()}
        except CapabilityError as exc:
            return {"ok": False, "detail": exc.message}

    async def analyze(
        self,
        *,
        image: bytes | None = None,
        filename: str | None = None,
        image_url: str | None = None,
        options: dict[str, Any] | None = None,
    ) -> dict:
        """调用 /analyze；长边 >4000px 或下游强制异步时自动轮询 /tasks/{id} 至完成。

        VA 不使用下游自己的 callback（其投递不可靠），轮询兜底在 VA 侧完成。
        """
        data: dict[str, str] = {}
        files: dict[str, tuple] | None = None
        if image is not None:
            files = {"file": (filename or "upload.png", image)}
        elif image_url:
            data["url"] = image_url
        else:
            raise CapabilityError(self.name, "BAD_REQUEST", "缺少图片：需提供 image 文件或 image_url", http_status=400)
        if options:
            data["options"] = json.dumps(options, ensure_ascii=False)

        response = await self._call("POST", "/analyze", data=data, files=files, accepted=(200, 202))
        payload = response.json()
        if response.status_code == 202 and payload.get("task_id"):
            payload = await self._wait_task(str(payload["task_id"]))
        return payload

    async def _wait_task(self, task_id: str) -> dict:
        deadline = time.monotonic() + self.poll_timeout
        while True:
            response = await self._call("GET", f"/tasks/{task_id}", accepted=(200, 404))
            if response.status_code == 404:
                # 下游任务表在内存里，重启即丢 —— 明确告知而非无限等待
                raise CapabilityError(
                    self.name, "UNAVAILABLE",
                    f"OCR 下游任务 {task_id} 不存在（下游可能已重启）",
                    http_status=502,
                )
            task = response.json()
            status = task.get("status")
            if status == "done":
                return task.get("result") or {}
            if status == "error":
                raise CapabilityError(
                    self.name, "UPSTREAM_ERROR",
                    f"OCR 任务失败: {task.get('error')}",
                    http_status=502,
                )
            if time.monotonic() > deadline:
                raise CapabilityError(
                    self.name, "UPSTREAM_TIMEOUT",
                    f"OCR 任务 {task_id} 轮询超时（>{self.poll_timeout:.0f}s）",
                    http_status=504,
                )
            await asyncio.sleep(self.poll_interval)
