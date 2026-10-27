"""能力注册表：新增能力 = 实现 Provider 子类 + 在这里注册。"""

from __future__ import annotations

import httpx

from ..config import Settings
from .base import CapabilityError, CapabilityProvider
from .ipcheck import IpCheckProvider
from .ocr import OcrProvider

__all__ = [
    "CapabilityError",
    "CapabilityProvider",
    "IpCheckProvider",
    "OcrProvider",
    "build_providers",
]


def build_providers(
    settings: Settings,
    transports: dict[str, httpx.AsyncBaseTransport] | None = None,
) -> dict[str, CapabilityProvider]:
    """按配置构建全部能力提供者；transports 仅供测试注入假下游。"""
    transports = transports or {}
    providers: dict[str, CapabilityProvider] = {
        "ocr": OcrProvider(
            base_url=settings.ocr_base_url,
            timeout=settings.http_timeout_seconds,
            concurrency=settings.ocr_concurrency,
            poll_interval=settings.ocr_poll_interval_seconds,
            poll_timeout=settings.ocr_poll_timeout_seconds,
            transport=transports.get("ocr"),
        ),
        "ipcheck": IpCheckProvider(
            base_url=settings.ipcheck_base_url,
            timeout=settings.http_timeout_seconds,
            concurrency=settings.ipcheck_concurrency,
            transport=transports.get("ipcheck"),
        ),
    }
    return providers
