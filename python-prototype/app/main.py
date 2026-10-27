"""vision-assistant 入口：uvicorn app.main:app --host 0.0.0.0 --port 9000"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from .capabilities import build_providers
from .config import Settings, get_settings
from .db import Database
from .errors import install_error_handlers
from .routers import build_api_router, build_health_router
from .tasks import TaskDao, TaskManager

logger = logging.getLogger("va")

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"


def create_app(
    settings: Settings | None = None,
    transports: dict[str, httpx.AsyncBaseTransport] | None = None,
    webhook_transport: httpx.AsyncBaseTransport | None = None,
) -> FastAPI:
    """构建 VA 应用。transports / webhook_transport 是测试注入假下游的接缝。"""
    settings = settings or get_settings()
    if not settings.auth_enabled:
        logger.warning("VA_API_KEYS 为空：接口鉴权已关闭（仅限本机开发环境）")

    db = Database(settings.data_dir / "va.sqlite3")
    dao = TaskDao(db)
    providers = build_providers(settings, transports)
    task_manager = TaskManager(dao, providers, settings, webhook_transport=webhook_transport)
    interrupted = task_manager.recover_interrupted()
    if interrupted:
        logger.warning("启动恢复：%d 个上次未完成的任务已标记为 INTERRUPTED", interrupted)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        for provider in providers.values():
            await provider.aclose()

    app = FastAPI(title="vision-assistant", version="0.1.0", lifespan=lifespan)
    app.state.settings = settings
    app.state.providers = providers
    app.state.task_manager = task_manager

    install_error_handlers(app)
    if settings.cors_origin_list:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origin_list,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    app.include_router(build_health_router())
    app.include_router(build_api_router())

    if STATIC_DIR.is_dir():
        app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="web")
    else:  # pragma: no cover
        logger.warning("静态目录不存在，C端页面未挂载: %s", STATIC_DIR)

    return app


app = create_app()
