package com.bz.va.client;

import com.bz.va.config.VaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ocr-agent 适配器（/analyze 核心接口）。
 *
 * - 并发闸门：OCR 推理全局串行（下游有并发毒化事故史），Semaphore 排队保护；
 * - 大图：下游返回 202 + task_id 时自动轮询 /tasks/{id} 至完成（VA 不依赖下游回调，轮询兜底）；
 * - 下游任务表在内存、重启即丢：404 转为明确错误而非无限等待。
 */
@Component
public class OcrClient extends AbstractDownstreamClient {

    private final Semaphore gate;
    private final Duration pollInterval;
    private final Duration pollTimeout;

    public OcrClient(VaProperties properties,
            @Qualifier("ocrRestClient") RestClient client,
            @Qualifier("ocrHealthRestClient") RestClient healthClient,
            ObjectMapper mapper) {
        super("ocr", client, healthClient, mapper);
        this.gate = new Semaphore(Math.max(1, properties.getOcrConcurrency()));
        this.pollInterval = properties.getOcrPollInterval();
        this.pollTimeout = properties.getOcrPollTimeout();
    }

    public JsonNode health() {
        return health("/healthz");
    }

    /**
     * OCR 识别。image 与 url 二选一；options 为 ocr-agent /analyze 的 options JSON（可空）。
     */
    public JsonNode analyze(byte[] image, String filename, String url, JsonNode options) {
        var parts = form();
        if (image != null) {
            parts.add("file", resource(image, filename));
        } else if (url != null && !url.isBlank()) {
            parts.add("url", url);
        } else {
            throw new DownstreamException(provider(), "BAD_REQUEST", 400, "缺少图片：需提供 image 文件或 url", null);
        }
        if (options != null && !options.isNull() && !options.isEmpty()) {
            parts.add("options", options.toString());
        }

        gate.acquireUninterruptibly();
        try {
            ResponseWithStatus response = postMultipartCaptureStatus("/analyze", parts);
            JsonNode payload = response.body();
            if (response.status() == 202 && payload.hasNonNull("task_id")) {
                payload = waitTask(payload.get("task_id").asText());
            }
            return payload;
        } finally {
            gate.release();
        }
    }

    private JsonNode waitTask(String taskId) {
        long deadline = System.nanoTime() + pollTimeout.toNanos();
        while (true) {
            JsonNode task = getJson("/tasks/{id}", taskId);
            String status = task.path("status").asText("");
            if ("done".equals(status)) {
                return task.path("result");
            }
            if ("error".equals(status)) {
                throw new DownstreamException(provider(), "UPSTREAM_ERROR", 502,
                        "OCR 任务失败: " + task.path("error").asText(null), null);
            }
            if (System.nanoTime() > deadline) {
                throw new DownstreamException(provider(), "UPSTREAM_TIMEOUT", 504,
                        "OCR 任务 " + taskId + " 轮询超时（>" + pollTimeout.toSeconds() + "s）", null);
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownstreamException(provider(), "UPSTREAM_TIMEOUT", 504,
                        "OCR 任务轮询被中断: " + taskId, null);
            }
        }
    }
}
