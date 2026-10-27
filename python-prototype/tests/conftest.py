"""测试基础设施：MockTransport 模拟 ocr-agent / ip_check 下游，ASGITransport 直连 VA。"""

from __future__ import annotations

import contextlib
from typing import Any

import httpx
import pytest

from app.config import Settings
from app.main import create_app

# ── 假下游数据（契约来自两服务对接文档）──

OCR_ANALYZE: dict[str, Any] = {
    "image_meta": {"width": 1000, "height": 800, "tile_count": 1, "crop": [10, 20, 990, 780]},
    "items": [
        {"id": "t1", "type": "text", "text": "闪电熊", "bbox": [100, 200, 300, 240],
         "polygon": [[100, 200], [300, 200], [300, 240], [100, 240]], "confidence": 0.97, "source": "paddleocr"},
        {"id": "t2", "type": "art_text", "text": "草莓味", "bbox": [400, 500, 500, 530],
         "polygon": [], "confidence": 0.90, "source": "vlm"},
        {"id": "q1", "type": "qr", "content": "https://example.com", "bbox": [50, 50, 120, 120],
         "polygon": [], "confidence": 1.0, "source": "pyzbar"},
        {"id": "t3", "type": "text", "text": "   ", "bbox": [10, 10, 40, 30],
         "polygon": [], "confidence": 0.5, "source": "paddleocr"},
    ],
    "options_used": {},
    "annotated_image_b64": "SHOULD_BE_STRIPPED",
    "task_id": "fake-ocr-task",
    "stats": {},
}

IP_IDENTIFY: dict[str, Any] = {
    "bizno": "test-bizno",
    "proposer": "grounding-dino-tiny",
    "name_source": "ocr",
    "model": "dinov2-base",
    "thresholds": {"match_min": 0.75, "mismatch_max": 0.55},
    "region_stats": {"proposed": 2, "kept": 2, "identified": 2},
    "unpaired_names": ["草莓味"],
    "ocr_stats": {"received": 2, "name_candidates": 2, "role_matched": 1, "role_extracted": 1,
                  "dropped_degenerate": 0, "rejected": []},
    "regions": [
        {"bbox": {"x1": 0.05, "y1": 0.15, "x2": 0.45, "y2": 0.90}, "crop_box": {"x1": 50, "y1": 120, "x2": 450, "y2": 720},
         "score": 0.42, "paired": True, "pair_distance": 0.19, "pair_direction": "below",
         "name_bbox": {"x1": 0.08, "y1": 0.75, "x2": 0.40, "y2": 0.85},
         "printed_name": "闪电熊", "matched_role": "闪电熊", "role_name": "闪电熊",
         "similarity": 0.86, "verdict": "match", "name_consistent": True, "candidates": []},
        {"bbox": {"x1": 0.55, "y1": 0.15, "x2": 0.95, "y2": 0.90}, "crop_box": {"x1": 550, "y1": 120, "x2": 950, "y2": 720},
         "score": 0.40, "paired": True, "pair_distance": 0.22, "pair_direction": "below",
         "name_bbox": {"x1": 0.58, "y1": 0.75, "x2": 0.78, "y2": 0.85},
         "printed_name": "草莓味", "matched_role": None, "role_name": "其他熊",
         "similarity": 0.40, "verdict": "low_confidence", "name_consistent": None, "candidates": []},
    ],
    "elapsed_ms": 100,
}


def ocr_transport(captured: dict) -> httpx.MockTransport:
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET" and request.url.path == "/healthz":
            return httpx.Response(200, json={"status": "ok", "version": "fake"})
        if request.method == "POST" and request.url.path == "/analyze":
            captured["ocr_content"] = request.content
            return httpx.Response(200, json=OCR_ANALYZE)
        return httpx.Response(404, json={"detail": f"fake ocr: {request.method} {request.url.path}"})

    return httpx.MockTransport(handler)


def ipcheck_transport(captured: dict) -> httpx.MockTransport:
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "GET" and request.url.path == "/api/ip/health":
            return httpx.Response(200, json={"ok": True})
        if request.method == "POST" and request.url.path == "/api/ip/identify/auto":
            captured["ip_query"] = dict(request.url.params)
            captured["ip_content"] = request.content
            return httpx.Response(200, json=IP_IDENTIFY)
        return httpx.Response(404, json={"detail": f"fake ipcheck: {request.method} {request.url.path}"})

    return httpx.MockTransport(handler)


def webhook_transport(captured: dict, status_script: tuple[int, ...]) -> httpx.MockTransport:
    state = {"i": 0}

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.setdefault("webhooks", []).append(
            {"headers": dict(request.headers), "body": request.content}
        )
        code = status_script[min(state["i"], len(status_script) - 1)]
        state["i"] += 1
        return httpx.Response(code)

    return httpx.MockTransport(handler)


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(api_keys="key1,key2", data_dir=tmp_path / "data", _env_file=None)


@contextlib.asynccontextmanager
async def make_client(settings: Settings, *, webhook_transport: httpx.MockTransport | None = None):
    captured: dict = {}
    transports = {"ocr": ocr_transport(captured), "ipcheck": ipcheck_transport(captured)}
    app = create_app(settings, transports=transports, webhook_transport=webhook_transport)
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://va-test") as client:
        yield client, captured


@pytest.fixture
async def client(settings):
    async with make_client(settings) as pair:
        yield pair
