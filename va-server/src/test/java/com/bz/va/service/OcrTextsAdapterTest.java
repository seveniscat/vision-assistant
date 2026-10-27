package com.bz.va.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OcrTextsAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode analyze(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void filtersQrBarcodeAndBlankText() throws Exception {
        JsonNode payload = analyze("""
            {"image_meta": {"width": 1000, "height": 800, "crop": null}, "items": [
              {"type": "text", "text": "闪电熊", "bbox": [1, 2, 3, 4], "confidence": 0.9},
              {"type": "qr", "content": "https://x", "bbox": [5, 6, 7, 8]},
              {"type": "barcode", "content": "690123", "bbox": [9, 10, 11, 12]},
              {"type": "text", "text": "  ", "bbox": [13, 14, 15, 16]}
            ]}
            """);
        var texts = OcrTextsAdapter.toIpTexts(mapper, payload);
        assertThat(texts.size()).isEqualTo(1);
        assertThat(texts.get(0).path("text").asText()).isEqualTo("闪电熊");
    }

    @Test
    void appliesCropOffset() throws Exception {
        JsonNode payload = analyze("""
            {"image_meta": {"crop": [10, 20, 990, 780]}, "items": [
              {"type": "text", "text": "A", "bbox": [100, 200, 300, 240]}
            ]}
            """);
        var bbox = OcrTextsAdapter.toIpTexts(mapper, payload).get(0).path("bbox");
        assertThat(bbox.path("x1").asDouble()).isEqualTo(110.0);
        assertThat(bbox.path("y1").asDouble()).isEqualTo(220.0);
        assertThat(bbox.path("x2").asDouble()).isEqualTo(310.0);
        assertThat(bbox.path("y2").asDouble()).isEqualTo(260.0);
    }

    @Test
    void noCropMeansNoOffset() throws Exception {
        JsonNode payload = analyze("""
            {"image_meta": {"crop": null}, "items": [
              {"type": "art_text", "text": "A", "bbox": [1, 2, 3, 4]}
            ]}
            """);
        var bbox = OcrTextsAdapter.toIpTexts(mapper, payload).get(0).path("bbox");
        assertThat(bbox.path("x1").asDouble()).isEqualTo(1.0);
        assertThat(bbox.path("y2").asDouble()).isEqualTo(4.0);
    }

    @Test
    void passesConfidenceThrough() throws Exception {
        JsonNode payload = analyze("""
            {"image_meta": {}, "items": [
              {"type": "text", "text": "A", "bbox": [1, 2, 3, 4], "confidence": 0.87}
            ]}
            """);
        assertThat(OcrTextsAdapter.toIpTexts(mapper, payload).get(0).path("confidence").asDouble())
                .isEqualTo(0.87);
    }
}
