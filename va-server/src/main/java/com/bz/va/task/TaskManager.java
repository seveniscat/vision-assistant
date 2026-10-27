package com.bz.va.task;

import com.bz.va.client.DownstreamException;
import com.bz.va.config.VaProperties;
import com.bz.va.service.IpConsistencyService;
import com.bz.va.service.StepListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 异步任务管理：提交 → 线程池执行（步骤实时持久化）→ 结果落库 → 回调投递（重试 + 可选 HMAC 签名）。
 * 启动时把上次未完成的任务标记为 INTERRUPTED（结果可解释，不会永远 pending）。
 */
@Component
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private static final String RECIPE = "ip-consistency-full";
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final TaskDao dao;
    private final IpConsistencyService consistencyService;
    private final VaProperties properties;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final ExecutorService webhookExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "va-webhook");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient webhookHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public TaskManager(TaskDao dao, IpConsistencyService consistencyService, VaProperties properties,
            ObjectMapper mapper) {
        this.dao = dao;
        this.consistencyService = consistencyService;
        this.properties = properties;
        this.mapper = mapper;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getTaskConcurrency()), r -> {
            Thread t = new Thread(r, "va-task");
            t.setDaemon(true);
            return t;
        });
        int interrupted = dao.recoverInterrupted();
        if (interrupted > 0) {
            log.warn("启动恢复：{} 个上次未完成的任务已标记为 INTERRUPTED", interrupted);
        }
    }

    /** 提交一致性全检异步任务，立即返回 task_id。 */
    public String submitIpConsistency(byte[] image, String filename, String url, String bizno, String model,
            boolean includeCrops, String callbackUrl) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ObjectNode params = mapper.createObjectNode();
        params.put("bizno", bizno);
        if (model != null && !model.isBlank()) {
            params.put("model", model);
        }
        if (image != null) {
            // 不落盘图片字节；只登记元信息
            params.putObject("_upload").put("filename", filename == null ? "" : filename)
                    .put("bytes", image.length);
        } else {
            params.put("image_url", url);
        }
        dao.create(id, RECIPE, params.toString(), callbackUrl);
        try {
            executor.submit(() -> execute(id, image, filename, url, bizno, model, includeCrops, callbackUrl));
        } catch (RejectedExecutionException e) {
            dao.finish(id, "error", null,
                    "{\"code\": \"INTERNAL\", \"message\": \"任务线程池已关闭\"}", null);
            throw e;
        }
        return id;
    }

    public java.util.Optional<TaskRecord> get(String id) {
        return dao.get(id);
    }

    public java.util.List<TaskRecord> listRecent(int limit) {
        return dao.listRecent(limit);
    }

    // ── 执行 ──

    private void execute(String id, byte[] image, String filename, String url, String bizno, String model,
            boolean includeCrops, String callbackUrl) {
        dao.markRunning(id);
        ArrayNode steps = mapper.createArrayNode();
        StepListener listener = (name, ok, elapsedMs, detail) -> {
            ObjectNode step = steps.addObject();
            step.put("name", name);
            step.put("status", ok ? "ok" : "error");
            step.put("elapsed_ms", elapsedMs);
            if (detail != null) {
                step.put("detail", detail);
            }
            dao.updateSteps(id, steps.toString());
        };
        try {
            ObjectNode report = consistencyService.fullCheck(image, filename, url, bizno, model,
                    includeCrops, listener);
            dao.finish(id, "done", report.toString(), null, steps.toString());
        } catch (DownstreamException e) {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", e.getCode());
            error.put("message", e.getMessage());
            error.put("provider", e.getProvider());
            if (e.getDetail() != null) {
                error.set("detail", e.getDetail());
            }
            dao.finish(id, "error", null, error.toString(), steps.toString());
        } catch (RuntimeException e) {
            log.error("task {} failed", id, e);
            dao.finish(id, "error", null,
                    "{\"code\": \"INTERNAL\", \"message\": \"流水线执行异常: " + jsonEscape(e.getMessage()) + "\"}",
                    steps.toString());
        }
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            webhookExecutor.submit(() -> deliverWebhook(id, callbackUrl));
        }
    }

    // ── 回调 ──

    private void deliverWebhook(String taskId, String callbackUrl) {
        TaskRecord task = dao.get(taskId).orElse(null);
        if (task == null) {
            return;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("event", "done".equals(task.status()) ? "task.completed" : "task.failed");
        payload.put("task_id", taskId);
        payload.put("recipe", task.type());
        payload.put("status", task.status());
        if (task.errorJson() != null) {
            try {
                payload.set("error", mapper.readTree(task.errorJson()));
            } catch (Exception ignored) {
                payload.put("error", task.errorJson());
            }
        } else {
            payload.putNull("error");
        }
        payload.put("timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(TS));

        byte[] body;
        try {
            body = mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            log.error("webhook payload 序列化失败: {}", e.getMessage());
            return;
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(callbackUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        String secret = properties.getWebhookSecret();
        if (secret != null && !secret.isBlank()) {
            requestBuilder.header("X-VA-Signature", hmacSha256(secret, body));
        }

        for (int attempt = 1; attempt <= properties.getTaskWebhookMaxAttempts(); attempt++) {
            String code;
            boolean ok;
            try {
                HttpResponse<Void> response =
                        webhookHttp.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
                ok = response.statusCode() >= 200 && response.statusCode() < 300;
                code = String.valueOf(response.statusCode());
            } catch (Exception e) {
                ok = false;
                code = "transport:" + e.getClass().getSimpleName();
            }
            dao.webhookProgress(taskId, attempt, code);
            if (ok) {
                return;
            }
            log.warn("webhook {} 第 {}/{} 次投递失败: {}", callbackUrl, attempt,
                    properties.getTaskWebhookMaxAttempts(), code);
            if (attempt < properties.getTaskWebhookMaxAttempts()) {
                try {
                    Thread.sleep(properties.getTaskWebhookBackoff().toMillis() * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static String hmacSha256(String secret, byte[] body) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static String jsonEscape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\'")
                .replace("\n", " ").replace("\r", " ");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        webhookExecutor.shutdown();
    }
}
