package com.bz.va.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ocr-agent /analyze 响应 → identify/auto 的 ocr_texts 适配。
 *
 * 契约来自 packvision docs/ip-check-api.md §6.1 与 scripts/e2e_ocr_consistency.py：
 * - 只取 type ∈ {text, art_text}；qr/barcode 内容不是角色名称；
 * - items bbox 基于裁白边后的图，image_meta.crop 非 null 时加 crop[:2] 偏移映回原图；
 * - 像素坐标直传（下游检测到 >1 自动按原图宽高归一化）；
 * - confidence 原样附带（下游忽略未知字段）。
 */
public final class OcrTextsAdapter {

    private OcrTextsAdapter() {
    }

    public static ArrayNode toIpTexts(com.fasterxml.jackson.databind.ObjectMapper mapper, JsonNode analyze) {
        JsonNode meta = analyze.path("image_meta");
        double offsetX = 0.0;
        double offsetY = 0.0;
        JsonNode crop = meta.path("crop");
        if (crop.isArray() && crop.size() >= 2) {
            offsetX = crop.get(0).asDouble();
            offsetY = crop.get(1).asDouble();
        }

        ArrayNode texts = mapper.createArrayNode();
        for (JsonNode item : analyze.path("items")) {
            String type = item.path("type").asText("");
            if (!"text".equals(type) && !"art_text".equals(type)) {
                continue;
            }
            String text = item.path("text").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            JsonNode bbox = item.path("bbox");
            if (!bbox.isArray() || bbox.size() < 4) {
                continue;
            }
            ObjectNode entry = texts.addObject();
            entry.put("text", text);
            ObjectNode box = entry.putObject("bbox");
            box.put("x1", bbox.get(0).asDouble() + offsetX);
            box.put("y1", bbox.get(1).asDouble() + offsetY);
            box.put("x2", bbox.get(2).asDouble() + offsetX);
            box.put("y2", bbox.get(3).asDouble() + offsetY);
            if (item.hasNonNull("confidence")) {
                entry.put("confidence", item.path("confidence").asDouble());
            }
        }
        return texts;
    }
}
