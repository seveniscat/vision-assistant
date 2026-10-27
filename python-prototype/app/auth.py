"""B端接口鉴权：X-API-Key 白名单（VA_API_KEYS 为空时视为开发模式放行）。"""

from __future__ import annotations

from fastapi import Header, Request

from .errors import VaError


def require_api_key(request: Request, x_api_key: str | None = Header(default=None, alias="X-API-Key")) -> None:
    settings = request.app.state.settings
    keys = settings.api_key_list
    if not keys:
        return  # 开发模式：未配置白名单，放行
    if x_api_key is None or x_api_key not in keys:
        raise VaError("UNAUTHORIZED", "缺少或无效的 X-API-Key", http_status=401)
