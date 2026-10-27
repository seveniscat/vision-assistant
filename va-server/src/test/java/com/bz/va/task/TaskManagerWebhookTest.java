package com.bz.va.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bz.va.config.VaProperties;
import com.bz.va.service.IpConsistencyService;
import com.bz.va.service.StepListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** TaskManager 端到端（mock 下游编排服务 + JDK HttpServer 假回调接收方）：执行、步骤持久化、webhook 重试与签名。 */
class TaskManagerWebhookTest {

    @TempDir
    Path tempDir;

    private HttpServer callbackServer;
    private final List<Captured> hits = new CopyOnWriteArrayList<>();
    private TaskManager manager;
    private TaskDao dao;

    record Captured(String signature, byte[] body) {
    }

    @BeforeEach
    void setUp() throws Exception {
        callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        callbackServer.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            hits.add(new Captured(exchange.getRequestHeaders().getFirst("X-VA-Signature"), body));
            // 前两次 500，第三次 204：验证指数退避重试
            int code = hits.size() < 3 ? 500 : 204;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        callbackServer.start();

        Path db = Files.createTempFile(tempDir, "va", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        ds.setConfig(config);
        dao = new TaskDao(ds);

        ObjectMapper mapper = new ObjectMapper();
        IpConsistencyService service = mock(IpConsistencyService.class);
        when(service.fullCheck(any(), any(), any(), eq("biz-1"), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    StepListener listener = invocation.getArgument(6);
                    listener.onStep("ocr.analyze", true, 12, "items=3");
                    listener.onStep("ipcheck.identify_auto", true, 34, "regions=2");
                    ObjectNode report = mapper.createObjectNode();
                    report.put("bizno", "biz-1");
                    return report;
                });

        VaProperties properties = new VaProperties();
        properties.setTaskWebhookMaxAttempts(3);
        properties.setTaskWebhookBackoff(java.time.Duration.ofMillis(20));
        properties.setWebhookSecret("s3cret");

        manager = new TaskManager(dao, service, properties, mapper);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        if (callbackServer != null) {
            callbackServer.stop(0);
        }
    }

    @Test
    void executesPersistsStepsAndRetriesWebhookWithSignature() throws Exception {
        String callbackUrl = "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/hook";
        String taskId = manager.submitIpConsistency(new byte[] {1, 2, 3}, "pack.png", null,
                "biz-1", null, false, callbackUrl);

        await(() -> dao.get(taskId).orElseThrow().status().equals("done"));
        await(() -> dao.get(taskId).orElseThrow().callbackAttempts() >= 3);

        TaskRecord task = dao.get(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo("done");
        assertThat(task.resultJson()).contains("bizno");
        assertThat(task.stepsJson()).contains("ocr.analyze").contains("regions=2");
        assertThat(task.callbackLastCode()).isEqualTo("204");

        // 重试了 3 次（500/500/204），事件载荷带 task.completed 与 HMAC 签名
        assertThat(hits).hasSize(3);
        Captured last = hits.get(2);
        String payload = new String(last.body(), StandardCharsets.UTF_8);
        assertThat(payload).contains("task.completed").contains(taskId);
        assertThat(last.signature()).isEqualTo(hmacSha256("s3cret", last.body()));
    }

    @Test
    void downstreamFailureIsPersistedAsError() throws Exception {
        IpConsistencyService failing = mock(IpConsistencyService.class);
        when(failing.fullCheck(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    // 模拟真实编排行为：失败步骤先回调（记录）再抛出
                    StepListener listener = invocation.getArgument(6);
                    listener.onStep("ocr.analyze", false, 5, "ocr 不可达");
                    throw new com.bz.va.client.DownstreamException(
                            "ocr", "UNAVAILABLE", 502, "ocr 不可达", null);
                });
        Path db = Files.createTempFile(tempDir, "va", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        TaskDao failingDao = new TaskDao(ds);
        VaProperties properties = new VaProperties();
        properties.setTaskWebhookMaxAttempts(1);
        TaskManager m = new TaskManager(failingDao, failing, properties, new ObjectMapper());
        try {
            String taskId = m.submitIpConsistency(new byte[] {1}, "p.png", null, "biz-2", null, false, null);
            await(() -> failingDao.get(taskId).orElseThrow().status().equals("error"));
            TaskRecord task = failingDao.get(taskId).orElseThrow();
            assertThat(task.errorJson()).contains("UNAVAILABLE").contains("ocr");
            assertThat(task.stepsJson()).contains("error");
        } finally {
            m.shutdown();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("condition not met within timeout");
    }

    private static String hmacSha256(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
