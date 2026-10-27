package com.bz.va.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

class TaskDaoTest {

    @TempDir
    Path tempDir;

    private TaskDao newDao() throws Exception {
        Path db = Files.createTempFile(tempDir, "va", ".sqlite");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        ds.setConfig(config);
        return new TaskDao(ds);
    }

    @Test
    void taskLifecycleIsPersisted() throws Exception {
        TaskDao dao = newDao();
        dao.create("t1", "ip-consistency-full", "{\"bizno\":\"b\"}", "http://cb/hook");

        TaskRecord created = dao.get("t1").orElseThrow();
        assertThat(created.status()).isEqualTo("pending");
        assertThat(created.callbackUrl()).isEqualTo("http://cb/hook");
        assertThat(created.createdAt()).isNotBlank();

        dao.markRunning("t1");
        dao.updateSteps("t1", "[{\"name\":\"ocr.analyze\",\"status\":\"ok\"}]");
        dao.finish("t1", "done", "{\"summary\":{}}", null,
                "[{\"name\":\"ocr.analyze\",\"status\":\"ok\"}]");

        TaskRecord done = dao.get("t1").orElseThrow();
        assertThat(done.status()).isEqualTo("done");
        assertThat(done.resultJson()).contains("summary");
        assertThat(done.finishedAt()).isNotBlank();

        dao.webhookProgress("t1", 2, "204");
        assertThat(dao.get("t1").orElseThrow().callbackAttempts()).isEqualTo(2);

        List<TaskRecord> recent = dao.listRecent(10);
        assertThat(recent).hasSize(1);
    }

    @Test
    void recoverInterruptedMarksOnlyInflightTasks() throws Exception {
        TaskDao dao = newDao();
        dao.create("pending", "r", "{}", null);
        dao.create("running", "r", "{}", null);
        dao.create("t3", "r", "{}", null);
        dao.markRunning("running");
        dao.finish("t3", "done", "{}", null, null);

        assertThat(dao.recoverInterrupted()).isEqualTo(2);
        assertThat(dao.get("pending").orElseThrow().status()).isEqualTo("error");
        assertThat(dao.get("running").orElseThrow().errorJson()).contains("INTERRUPTED");
        assertThat(dao.get("t3").orElseThrow().status()).isEqualTo("done");

        // 已恢复过的不再重复处理
        assertThat(dao.recoverInterrupted()).isZero();
    }
}
