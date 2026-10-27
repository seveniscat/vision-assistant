"""Recipe：包装一致性全检 —— ocr-agent /analyze → 适配 ocr_texts → ip_check identify/auto → 汇总报告。

适配契约来自 packvision docs/ip-check-api.md §6.1 与 scripts/e2e_ocr_consistency.py：
- 只取 type ∈ {text, art_text}；qr/barcode 内容不是角色名称；
- items bbox 基于裁白边后的图，image_meta.crop 非 null 时加 crop[:2] 偏移映回原图；
- 像素坐标直传（下游检测到 >1 自动归一化），confidence 原样附带。
"""

from __future__ import annotations

from typing import Any, Mapping

from pydantic import BaseModel, Field

from ..capabilities.ipcheck import IpCheckProvider
from ..capabilities.ocr import OcrProvider
from .base import MediaInput, Recipe, RunContext, register


def ocr_payload_to_ip_texts(analyze: Mapping[str, Any]) -> list[dict]:
    """ocr-agent /analyze 响应 → identify/auto 的 ocr_texts（见模块 docstring 契约）。"""
    meta = analyze.get("image_meta") or {}
    crop = meta.get("crop")
    offset_x, offset_y = (float(crop[0]), float(crop[1])) if crop else (0.0, 0.0)

    texts: list[dict] = []
    for item in analyze.get("items", []):
        if item.get("type") not in ("text", "art_text"):
            continue
        text = str(item.get("text") or "").strip()
        if not text:
            continue
        x1, y1, x2, y2 = item["bbox"]
        texts.append(
            {
                "text": text,
                "bbox": {
                    "x1": x1 + offset_x, "y1": y1 + offset_y,
                    "x2": x2 + offset_x, "y2": y2 + offset_y,
                },
                "confidence": item.get("confidence"),
            }
        )
    return texts


def build_summary(ip_payload: Mapping[str, Any], ocr_texts: list[dict]) -> dict:
    """把 identify/auto 结果折叠为业务可判读的结论。"""
    regions = ip_payload.get("regions", [])
    verdicts = {"match": 0, "mismatch": 0, "low_confidence": 0}
    name_inconsistent: list[dict] = []
    paired = 0
    for region in regions:
        verdict = region.get("verdict")
        if verdict in verdicts:
            verdicts[verdict] += 1
        if region.get("paired"):
            paired += 1
            if region.get("name_consistent") is False:
                name_inconsistent.append(
                    {
                        "printed_name": region.get("printed_name"),
                        "matched_role": region.get("matched_role"),
                        "role_name": region.get("role_name"),
                        "similarity": region.get("similarity"),
                    }
                )

    if verdicts["mismatch"]:
        overall = "fail"          # 用错形象 / 未授权 IP
    elif verdicts["low_confidence"] or name_inconsistent or not regions:
        overall = "review"        # 身份不确定 / 图文错位 / 未检出区域，需人工复核
    else:
        overall = "pass"

    return {
        "overall": overall,
        "regions_total": len(regions),
        "regions_paired": paired,
        "verdicts": verdicts,
        "name_inconsistent": name_inconsistent,
        "unpaired_names": ip_payload.get("unpaired_names", []),
        "ocr_texts_passed": len(ocr_texts),
    }


class IpConsistencyParams(BaseModel):
    bizno: str = Field(min_length=1, description="IP 标准库的业务项目编号")
    image_url: str | None = None
    model: str | None = None
    aggregate: str | None = None
    match_min: float | None = Field(default=None, ge=0, le=1)
    mismatch_max: float | None = Field(default=None, ge=0, le=1)
    include_crops: bool = False
    score_threshold: float | None = Field(default=None, ge=0.01, le=0.95)
    proposer: str | None = None
    tiling: str | None = None
    prompt: str | None = None
    ocr_options: dict[str, Any] = Field(default_factory=dict, description="透传 ocr-agent /analyze 的 options")


class IpConsistencyFullRecipe(Recipe):
    name = "ip-consistency-full"
    title = "包装一致性全检"
    description = "OCR 全量文本框 + 卡通形象自动框选识别 + 图文一致性判定，一次调用输出完整报告"

    Params = IpConsistencyParams

    async def run(self, ctx: RunContext, params: IpConsistencyParams, media: MediaInput) -> dict:
        ocr: OcrProvider = ctx.providers["ocr"]  # type: ignore[assignment]
        ipcheck: IpCheckProvider = ctx.providers["ipcheck"]  # type: ignore[assignment]

        async with ctx.step("ocr.analyze") as step:
            ocr_payload = await ocr.analyze(
                image=media.data, filename=media.filename, image_url=media.url,
                options=params.ocr_options or None,
            )
            step.detail["items"] = len(ocr_payload.get("items", []))

        ocr_texts = ocr_payload_to_ip_texts(ocr_payload)

        async with ctx.step("ipcheck.identify_auto") as step:
            ip_payload = await ipcheck.identify_auto(
                bizno=params.bizno,
                image=media.data, filename=media.filename, image_url=media.url,
                ocr_texts=ocr_texts,
                model=params.model, aggregate=params.aggregate,
                match_min=params.match_min, mismatch_max=params.mismatch_max,
                include_crops=params.include_crops, score_threshold=params.score_threshold,
                proposer=params.proposer, tiling=params.tiling, prompt=params.prompt,
            )
            step.detail["regions"] = len(ip_payload.get("regions", []))

        async with ctx.step("report"):
            summary = build_summary(ip_payload, ocr_texts)

        # annotated_image_b64 体积大且 C端按坐标自绘，默认丢弃
        ocr_view = {k: v for k, v in ocr_payload.items() if k != "annotated_image_b64"}
        return {
            "recipe": self.name,
            "bizno": params.bizno,
            "image_meta": ocr_payload.get("image_meta"),
            "summary": summary,
            "ocr": ocr_view,
            "ip": ip_payload,
        }


register(IpConsistencyFullRecipe())
