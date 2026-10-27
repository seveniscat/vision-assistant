"""能力提供者（Provider）基类：统一封装下游调用的超时、并发闸门与错误归一化。

所有下游服务（ocr-agent / packvision ip_check / 未来的扩展能力）都实现为
CapabilityProvider 子类；编排层只面对 Provider，不感知 HTTP 细节。
"""

from __future__ import annotations

import asyncio
from typing import Any

import httpx

# 下游状态码 → VA 归一化错误码
_STATUS_CODE_MAP = {
    400: "BAD_REQUEST",
    404: "NOT_FOUND",
    409: "CONFLICT",
    413: "PAYLOAD_TOO_LARGE",
    415: "UNSUPPORTED_MEDIA_TYPE",
    422: "UNPROCESSABLE",
    503: "UNAVAILABLE",
}


class CapabilityError(Exception):
    """下游能力调用失败（网络/超时/下游业务错误），已归一化为 VA 错误码。"""

    def __init__(
        self,
        provider: str,
        code: str,
        message: str,
        *,
        http_status: int = 502,
        detail: Any = None,
    ):
        super().__init__(message)
        self.provider = provider
        self.code = code
        self.message = message
        self.http_status = http_status
        self.detail = detail

    def to_dict(self) -> dict:
        payload: dict[str, Any] = {"code": self.code, "message": self.message, "provider": self.provider}
        if self.detail is not None:
            payload["detail"] = self.detail
        return payload


class CapabilityProvider:
    """下游能力适配器基类。

    - 并发闸门：每个 provider 一个 Semaphore（OCR 推理串行 → 1），保护下游；
    - 超时：默认使用构造时的 timeout，health 检查用短超时；
    - 错误：非预期状态码 / 网络错误 / 超时统一抛 CapabilityError。
    """

    name: str = "capability"
    # 供 /capabilities 元信息使用：本 provider 暴露的操作说明
    operations: dict[str, str] = {}

    def __init__(
        self,
        *,
        base_url: str,
        timeout: float,
        concurrency: int = 1,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._gate = asyncio.Semaphore(concurrency)
        self._client = httpx.AsyncClient(base_url=self.base_url, timeout=timeout, transport=transport)

    async def aclose(self) -> None:
        await self._client.aclose()

    async def health(self) -> dict:
        raise NotImplementedError

    async def _call(
        self,
        method: str,
        path: str,
        *,
        params: dict | None = None,
        data: dict | None = None,
        files: dict | None = None,
        accepted: tuple[int, ...] = (200,),
        timeout: float | None = None,
    ) -> httpx.Response:
        async with self._gate:
            try:
                response = await self._client.request(
                    method,
                    path,
                    params=params,
                    data=data,
                    files=files,
                    timeout=timeout if timeout is not None else self.timeout,
                )
            except httpx.TimeoutException as exc:
                raise CapabilityError(
                    self.name, "UPSTREAM_TIMEOUT",
                    f"{self.name} 请求超时: {method} {path}",
                    http_status=504,
                ) from exc
            except httpx.HTTPError as exc:
                raise CapabilityError(
                    self.name, "UNAVAILABLE",
                    f"{self.name} 不可达（{self.base_url}）: {exc}",
                    http_status=502,
                ) from exc

        if response.status_code not in accepted:
            try:
                detail: Any = response.json()
            except ValueError:
                detail = response.text[:500]
            code = _STATUS_CODE_MAP.get(response.status_code, "UPSTREAM_ERROR")
            # 4xx 是调用侧语义错误，状态码透传；5xx 归为网关错误
            http_status = response.status_code if response.status_code < 500 else 502
            raise CapabilityError(
                self.name, code,
                f"{self.name} {path} 返回 {response.status_code}",
                http_status=http_status,
                detail=detail,
            )
        return response
