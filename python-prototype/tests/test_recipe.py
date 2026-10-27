"""流水线纯函数单测：ocr_texts 适配 + summary 汇总。"""

from app.orchestration.ip_consistency import build_summary, ocr_payload_to_ip_texts


def _analyze(items, crop=None):
    return {"image_meta": {"width": 1000, "height": 800, "crop": crop}, "items": items}


class TestOcrAdapt:
    def test_filters_qr_barcode_and_blank(self):
        payload = _analyze([
            {"type": "text", "text": "闪电熊", "bbox": [1, 2, 3, 4], "confidence": 0.9},
            {"type": "qr", "content": "https://x", "bbox": [5, 6, 7, 8]},
            {"type": "barcode", "content": "690123", "bbox": [9, 10, 11, 12]},
            {"type": "text", "text": "  ", "bbox": [13, 14, 15, 16]},
        ])
        texts = ocr_payload_to_ip_texts(payload)
        assert [t["text"] for t in texts] == ["闪电熊"]

    def test_crop_offset_applied(self):
        texts = ocr_payload_to_ip_texts(_analyze(
            [{"type": "text", "text": "A", "bbox": [100, 200, 300, 240]}], crop=[10, 20, 990, 780]
        ))
        assert texts[0]["bbox"] == {"x1": 110, "y1": 220, "x2": 310, "y2": 260}

    def test_no_crop_no_offset(self):
        texts = ocr_payload_to_ip_texts(_analyze(
            [{"type": "art_text", "text": "A", "bbox": [1, 2, 3, 4]}], crop=None
        ))
        assert texts[0]["bbox"] == {"x1": 1, "y1": 2, "x2": 3, "y2": 4}

    def test_confidence_passthrough(self):
        texts = ocr_payload_to_ip_texts(_analyze(
            [{"type": "text", "text": "A", "bbox": [1, 2, 3, 4], "confidence": 0.87}]
        ))
        assert texts[0]["confidence"] == 0.87


def _region(verdict, name_consistent=None, paired=True, name="名", role="角", sim=0.8):
    return {"verdict": verdict, "name_consistent": name_consistent, "paired": paired,
            "printed_name": name, "matched_role": None, "role_name": role, "similarity": sim}


class TestSummary:
    def test_all_match_pass(self):
        s = build_summary({"regions": [_region("match", True)]}, [])
        assert s["overall"] == "pass"
        assert s["verdicts"] == {"match": 1, "mismatch": 0, "low_confidence": 0}

    def test_mismatch_fails(self):
        s = build_summary({"regions": [_region("match", True), _region("mismatch")]}, [])
        assert s["overall"] == "fail"

    def test_low_confidence_needs_review(self):
        s = build_summary({"regions": [_region("low_confidence")]}, [])
        assert s["overall"] == "review"

    def test_name_inconsistent_needs_review_and_listed(self):
        s = build_summary({"regions": [_region("match", False, name="错位名")]}, [])
        assert s["overall"] == "review"
        assert s["name_inconsistent"][0]["printed_name"] == "错位名"

    def test_no_regions_needs_review(self):
        s = build_summary({"regions": []}, [])
        assert s["overall"] == "review"
