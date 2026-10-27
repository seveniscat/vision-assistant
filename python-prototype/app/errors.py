"""统一错误模型：VA 所有接口的错误响应都是 {"error": {code, message, detail?}}。"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .capabilities.base import CapabilityError


class VaError(Exception):
    """VA 自身业务错误（参数/鉴权/未找到等），直接映射为统一错误响应。"""

    def __init__(self, code: str, message: str, *, http_status: int = 400, detail: Any = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status
        self.detail = detail

    def to_payload(self) -> dict:
        error: dict[str, Any] = {"code": self.code, "message": self.message}
        if self.detail is not None:
            error["detail"] = self.detail
        return {"error": error}


def _error_response(status: int, code: str, message: str, detail: Any = None) -> JSONResponse:
    error: dict[str, Any] = {"code": code, "message": message}
    if detail is not None:
        error["detail"] = detail
    return JSONResponse(status_code=status, content={"error": error})


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(VaError)
    async def _va_error(_: Request, exc: VaError) -> JSONResponse:
        return JSONResponse(status_code=exc.http_status, content=exc.to_payload())

    @app.exception_handler(CapabilityError)
    async def _capability_error(_: Request, exc: CapabilityError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.http_status,
            content={
                "error": {
                    "code": exc.code,
                    "message": exc.message,
                    "provider": exc.provider,
                    **({"detail": exc.detail} if exc.detail is not None else {}),
                }
            },
        )

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return _error_response(
            422, "INVALID_ARGUMENT", "请求参数校验失败", detail=exc.errors()
        )

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception) -> JSONResponse:  # pragma: no cover
        return _error_response(500, "INTERNAL", f"内部错误: {exc}")
