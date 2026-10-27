package com.bz.va.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** VA 配置（前缀 va，环境变量 VA_* 可覆盖，见 application.yml）。 */
@ConfigurationProperties(prefix = "va")
public class VaProperties {

    /** API Key 白名单；空 = 关闭鉴权（仅限本机开发） */
    private List<String> apiKeys = new ArrayList<>();

    private String ocrBaseUrl = "http://127.0.0.1:48763";
    private String ipcheckBaseUrl = "http://127.0.0.1:8001";
    private Duration httpTimeout = Duration.ofSeconds(600);
    /** OCR 推理闸门：下游全局串行，保持 1 */
    private int ocrConcurrency = 1;
    private Duration ocrPollInterval = Duration.ofSeconds(2);
    private Duration ocrPollTimeout = Duration.ofSeconds(900);

    /** 任务库（SQLite）目录 */
    private java.nio.file.Path dataDir = java.nio.file.Path.of("data");
    /** 流水线并发（OCR 推理仍由下游闸门串行保护） */
    private int taskConcurrency = 2;
    private int taskWebhookMaxAttempts = 3;
    private Duration taskWebhookBackoff = Duration.ofSeconds(2);
    /** 设置后任务回调携带 X-VA-Signature（HMAC-SHA256(body)） */
    private String webhookSecret = "";

    public boolean authEnabled() {
        return apiKeys != null && !apiKeys.isEmpty();
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public String getOcrBaseUrl() {
        return ocrBaseUrl;
    }

    public void setOcrBaseUrl(String ocrBaseUrl) {
        this.ocrBaseUrl = ocrBaseUrl;
    }

    public String getIpcheckBaseUrl() {
        return ipcheckBaseUrl;
    }

    public void setIpcheckBaseUrl(String ipcheckBaseUrl) {
        this.ipcheckBaseUrl = ipcheckBaseUrl;
    }

    public Duration getHttpTimeout() {
        return httpTimeout;
    }

    public void setHttpTimeout(Duration httpTimeout) {
        this.httpTimeout = httpTimeout;
    }

    public int getOcrConcurrency() {
        return ocrConcurrency;
    }

    public void setOcrConcurrency(int ocrConcurrency) {
        this.ocrConcurrency = ocrConcurrency;
    }

    public Duration getOcrPollInterval() {
        return ocrPollInterval;
    }

    public void setOcrPollInterval(Duration ocrPollInterval) {
        this.ocrPollInterval = ocrPollInterval;
    }

    public Duration getOcrPollTimeout() {
        return ocrPollTimeout;
    }

    public void setOcrPollTimeout(Duration ocrPollTimeout) {
        this.ocrPollTimeout = ocrPollTimeout;
    }

    public java.nio.file.Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(java.nio.file.Path dataDir) {
        this.dataDir = dataDir;
    }

    public int getTaskConcurrency() {
        return taskConcurrency;
    }

    public void setTaskConcurrency(int taskConcurrency) {
        this.taskConcurrency = taskConcurrency;
    }

    public int getTaskWebhookMaxAttempts() {
        return taskWebhookMaxAttempts;
    }

    public void setTaskWebhookMaxAttempts(int taskWebhookMaxAttempts) {
        this.taskWebhookMaxAttempts = taskWebhookMaxAttempts;
    }

    public Duration getTaskWebhookBackoff() {
        return taskWebhookBackoff;
    }

    public void setTaskWebhookBackoff(Duration taskWebhookBackoff) {
        this.taskWebhookBackoff = taskWebhookBackoff;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }
}
