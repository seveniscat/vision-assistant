"""packvision ip_check 适配器（identify/auto 核心接口）。"""

from __future__ import annotations

import json
from typing import Any

from .base import CapabilityError, CapabilityProvider


class IpCheckProvider(CapabilityProvider):
    name = "ipcheck"
    operations = {
        "identify_auto": "自动框选识别卡通形象 + 图文一致性判定（接收 OCR 文本框 ocr_texts）",
    }

    async def health(self) -> dict:
        try:
            response = await self._call("GET", "/api/ip/health", accepted=(200,), timeout=3.0)
            return {"ok": bool(response.json().get("ok")), "detail": response.json()}
        except CapabilityError as exc:
            return {"ok": False, "detail": exc.message}

    async def identify_auto(
        self,
        *,
        bizno: str,
        image: bytes | None = None,
        filename: str | None = None,
        image_url: str | None = None,
        ocr_texts: list[dict] | None = None,
        model: str | None = None,
        aggregate: str | None = None,
        match_min: float | None = None,
        mismatch_max: float | None = None,
        include_crops: bool = False,
        score_threshold: float | None = None,
        proposer: str | None = None,
        tiling: str | None = None,
        prompt: str | None = None,
    ) -> dict:
        data: dict[str, Any] = {}
        if ocr_texts is not None:
            data["ocr_texts"] = json.dumps(ocr_texts, ensure_ascii=False)
        # 可选参数只在显式传入时下发，保持下游默认值语义
        for key, value in (
            ("model", model), ("aggregate", aggregate), ("match_min", match_min),
            ("mismatch_max", mismatch_max), ("include_crops", include_crops if include_crops else None),
            ("score_threshold", score_threshold), ("proposer", proposer),
            ("tiling", tiling), ("prompt", prompt),
        ):
            if value is not None:
                data[key] = str(value).lower() if isinstance(value, bool) else value

        files: dict[str, tuple] | None = None
        if image is not None:
            files = {"image": (filename or "upload.png", image)}
        elif image_url:
            data["image_url"] = image_url
        else:
            raise CapabilityError(self.name, "BAD_REQUEST", "缺少图片：需提供 image 文件或 image_url", http_status=400)

        response = await self._call(
            "POST", "/api/ip/identify/auto",
            params={"bizno": bizno}, data=data, files=files, accepted=(200,),
        )
        return response.json()
