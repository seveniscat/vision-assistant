package com.bz.va.web;

import com.bz.va.client.IpCheckClient;
import com.bz.va.client.OcrClient;
import com.bz.va.config.VaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查（免鉴权）：VA 自身 + 下游能力状态。 */
@RestController
public class HealthController {

    private static final String VERSION = "0.3.0";

    private final OcrClient ocrClient;
    private final IpCheckClient ipCheckClient;
    private final VaProperties properties;
    private final ObjectMapper mapper;

    public HealthController(OcrClient ocrClient, IpCheckClient ipCheckClient, VaProperties properties,
            ObjectMapper mapper) {
        this.ocrClient = ocrClient;
        this.ipCheckClient = ipCheckClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("ocr", capability(ocrClient.health()));
        capabilities.put("ipcheck", capability(ipCheckClient.health()));
        return Map.of(
                "status", "ok",
                "service", "vision-assistant",
                "version", VERSION,
                "authRequired", properties.authEnabled(),
                "capabilities", capabilities);
    }

    private ObjectNode capability(JsonNode downstreamHealth) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", downstreamHealth != null);
        if (downstreamHealth != null) {
            node.set("detail", downstreamHealth);
        } else {
            node.put("detail", "下游不可达");
        }
        return node;
    }
}
