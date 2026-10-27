"""/api/v1 对外统一接口：健康（免鉴权）、能力元信息、原子 OCR、流水线、任务查询。"""

from __future__ import annotations

import asyncio
import json

from fastapi import APIRouter, Depends, File, Form, Query, Request, UploadFile
from pydantic import ValidationError

from ..auth import require_api_key
from ..errors import VaError
from ..orchestration import MediaInput, get_recipe, list_recipes

API_PREFIX = "/api/v1"
VERSION = "0.1.0"


# ── 健康检查（免鉴权）──

def build_health_router() -> APIRouter:
    router = APIRouter()

    @router.get(f"{API_PREFIX}/health")
    async def health(request: Request) -> dict:
        providers: dict = request.app.state.providers
        results = await asyncio.gather(*(p.health() for p in providers.values()), return_exceptions=True)
        capabilities = {
            provider.name: (
                result if isinstance(result, dict) else {"ok": False, "detail": str(result)}
            )
            for provider, result in zip(providers.values(), results)
        }
        settings = request.app.state.settings
        return {
            "status": "ok",
            "service": "vision-assistant",
            "version": VERSION,
            "auth_required": settings.auth_enabled,
            "capabilities": capabilities,
        }

    return router


# ── 业务接口（X-API-Key 鉴权）──

def build_api_router() -> APIRouter:
    router = APIRouter(prefix=API_PREFIX, dependencies=[Depends(require_api_key)])

    @router.get("/capabilities")
    async def capabilities(request: Request) -> dict:
        providers: dict = request.app.state.providers
        return {
            "providers": [
                {"name": p.name, "base_url": p.base_url, "operations": p.operations}
                for p in providers.values()
            ],
            "recipes": [
                {
                    "name": r.name,
                    "title": r.title,
                    "description": r.description,
                    "params_schema": r.Params.model_json_schema(),
                }
                for r in list_recipes()
            ],
        }

    @router.post("/ocr/analyze")
    async def ocr_analyze(
        request: Request,
        image: UploadFile | None = File(default=None),
        image_url: str | None = Form(default=None),
        options: str | None = Form(default=None),
    ) -> dict:
        """原子能力：单次 OCR 识别（同步返回，长图自动等待下游异步任务完成）。"""
        ocr = request.app.state.providers["ocr"]
        opts = None
        if options:
            try:
                opts = json.loads(options)
            except ValueError:
                raise VaError("INVALID_ARGUMENT", "options 不是合法 JSON", http_status=422)
        data = await image.read() if image is not None else None
        return await ocr.analyze(
            image=data,
            filename=image.filename if image is not None else None,
            image_url=image_url,
            options=opts,
        )

    @router.get("/recipes")
    async def recipes() -> dict:
        return {
            "recipes": [
                {"name": r.name, "title": r.title, "description": r.description}
                for r in list_recipes()
            ]
        }

    @router.post("/recipes/{name}/run", status_code=202)
    async def run_recipe(
        name: str,
        request: Request,
        image: UploadFile | None = File(default=None),
        image_url: str | None = Form(default=None),
        callback_url: str | None = Form(default=None),
        params: str | None = Form(default=None),
    ) -> dict:
        """启动一条流水线，立即返回 task_id；结果轮询 GET /api/v1/tasks/{task_id}。

        form 参数：
        - image / image_url：图片输入二选一
        - params：流水线参数 JSON（如 {"bizno": "customer-a"}；image_url 也可写在这里）
        - callback_url：任务完成后回调（带重试；设 VA_WEBHOOK_SECRET 时带 HMAC 签名）
        """
        recipe = get_recipe(name)
        if recipe is None:
            raise VaError("NOT_FOUND", f"未注册的流水线: {name}", http_status=404)

        raw: dict = {}
        if params:
            try:
                raw = json.loads(params)
            except ValueError:
                raise VaError("INVALID_ARGUMENT", "params 不是合法 JSON", http_status=422)
        if not isinstance(raw, dict):
            raise VaError("INVALID_ARGUMENT", "params 必须是 JSON 对象", http_status=422)
        if image_url and "image_url" not in raw:
            raw["image_url"] = image_url

        try:
            params_obj = recipe.Params.model_validate(raw)
        except ValidationError as exc:
            raise VaError("INVALID_ARGUMENT", "流水线参数校验失败", http_status=422, detail=exc.errors())

        data = await image.read() if image is not None else None
        media = MediaInput(data=data, filename=image.filename if image is not None else None, url=params_obj.image_url)
        try:
            media.validate()
        except ValueError as exc:
            raise VaError("INVALID_ARGUMENT", str(exc), http_status=400)

        task = request.app.state.task_manager.submit(name, params_obj, media, callback_url=callback_url)
        return {
            "task_id": task["task_id"],
            "recipe": name,
            "status": task["status"],
            "task_url": f"{API_PREFIX}/tasks/{task['task_id']}",
        }

    @router.get("/tasks")
    async def list_tasks(request: Request, limit: int = Query(default=50, ge=1, le=200)) -> dict:
        return {"tasks": request.app.state.task_manager.list_recent(limit)}

    @router.get("/tasks/{task_id}")
    async def get_task(request: Request, task_id: str) -> dict:
        task = request.app.state.task_manager.get(task_id)
        if task is None:
            raise VaError("NOT_FOUND", f"任务不存在: {task_id}", http_status=404)
        return task

    return router
