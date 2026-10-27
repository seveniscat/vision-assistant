package com.bz.va.service;

import com.bz.va.client.IpCheckClient;
import com.bz.va.client.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 编排：OCR 全量文本框 → 适配 ocr_texts → identify/auto → 汇总报告。 */
@Service
public class IpConsistencyService {

    private final OcrClient ocrClient;
    private final IpCheckClient ipCheckClient;
    private final ObjectMapper mapper;

    public IpConsistencyService(OcrClient ocrClient, IpCheckClient ipCheckClient, ObjectMapper mapper) {
        this.ocrClient = ocrClient;
        this.ipCheckClient = ipCheckClient;
        this.mapper = mapper;
    }

    public ObjectNode fullCheck(byte[] image, String filename, String url, String bizno, String model,
            boolean includeCrops) {
        return fullCheck(image, filename, url, bizno, model, includeCrops, StepListener.NOOP);
    }

    public ObjectNode fullCheck(byte[] image, String filename, String url, String bizno, String model,
            boolean includeCrops, StepListener listener) {
        JsonNode ocrPayload = step(listener, "ocr.analyze",
                () -> ocrClient.analyze(image, filename, url, null),
                payload -> "items=" + payload.path("items").size());
        var ocrTexts = OcrTextsAdapter.toIpTexts(mapper, ocrPayload);

        JsonNode ipPayload = step(listener, "ipcheck.identify_auto",
                () -> ipCheckClient.identifyAuto(bizno, image, filename, url, ocrTexts, model, includeCrops),
                payload -> "regions=" + payload.path("regions").size());

        return (ObjectNode) step(listener, "report", () -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("bizno", bizno);
            node.set("image_meta", ocrPayload.path("image_meta").deepCopy());
            node.set("summary", SummaryBuilder.build(mapper, ipPayload, ocrTexts.size()));
            ObjectNode ocrView = (ObjectNode) ocrPayload.deepCopy();
            ocrView.remove("annotated_image_b64"); // 体积大且前端按坐标自绘
            node.set("ocr", ocrView);
            node.set("ip", ipPayload);
            return node;
        }, node -> "overall=" + node.path("summary").path("overall").asText());
    }

    private JsonNode step(StepListener listener, String name, Supplier<JsonNode> body,
            Function<JsonNode, String> detail) {
        long started = System.nanoTime();
        try {
            JsonNode result = body.get();
            listener.onStep(name, true, (System.nanoTime() - started) / 1_000_000, detail.apply(result));
            return result;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            listener.onStep(name, false, (System.nanoTime() - started) / 1_000_000,
                    message.length() > 300 ? message.substring(0, 300) : message);
            throw e;
        }
    }
}
