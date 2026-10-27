package com.bz.va.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 把 identify/auto 结果折叠为业务可判读的结论（与 Python 原型同语义）。 */
public final class SummaryBuilder {

    private SummaryBuilder() {
    }

    /**
     * overall 判定规则：
     * - 任一 mismatch → fail（用错形象 / 未授权 IP）；
     * - 任一 low_confidence / 图文错位 / 未检出区域 → review（人工复核）；
     * - 否则 pass。
     */
    public static ObjectNode build(ObjectMapper mapper, JsonNode ipPayload, int ocrTextsCount) {
        int match = 0;
        int mismatch = 0;
        int lowConfidence = 0;
        int paired = 0;
        ArrayNode nameInconsistent = mapper.createArrayNode();

        for (JsonNode region : ipPayload.path("regions")) {
            String verdict = region.path("verdict").asText("");
            switch (verdict) {
                case "match" -> match++;
                case "mismatch" -> mismatch++;
                case "low_confidence" -> lowConfidence++;
                default -> {
                }
            }
            if (region.path("paired").asBoolean(false)) {
                paired++;
                if (region.path("name_consistent").isBoolean() && !region.path("name_consistent").asBoolean()) {
                    ObjectNode entry = nameInconsistent.addObject();
                    entry.put("printed_name", region.path("printed_name").asText(null));
                    entry.put("matched_role", region.path("matched_role").isTextual()
                            ? region.path("matched_role").asText() : null);
                    entry.put("role_name", region.path("role_name").isTextual()
                            ? region.path("role_name").asText() : null);
                    if (region.hasNonNull("similarity")) {
                        entry.put("similarity", region.path("similarity").asDouble());
                    }
                }
            }
        }

        int regionTotal = ipPayload.path("regions").size();
        String overall;
        if (mismatch > 0) {
            overall = "fail";
        } else if (lowConfidence > 0 || !nameInconsistent.isEmpty() || regionTotal == 0) {
            overall = "review";
        } else {
            overall = "pass";
        }

        ObjectNode verdicts = mapper.createObjectNode();
        verdicts.put("match", match);
        verdicts.put("mismatch", mismatch);
        verdicts.put("low_confidence", lowConfidence);

        ObjectNode summary = mapper.createObjectNode();
        summary.put("overall", overall);
        summary.put("regions_total", regionTotal);
        summary.put("regions_paired", paired);
        summary.set("verdicts", verdicts);
        summary.set("name_inconsistent", nameInconsistent);
        summary.set("unpaired_names", ipPayload.path("unpaired_names").deepCopy());
        summary.put("ocr_texts_passed", ocrTextsCount);
        return summary;
    }
}
