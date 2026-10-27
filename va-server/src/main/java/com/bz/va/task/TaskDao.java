package com.bz.va.task;

import java.sql.ResultSet;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 任务表 DAO。任务状态持久化在 SQLite——重启后已完成结果仍可查，在途任务由
 * {@link #recoverInterrupted()} 标记为被打断（可解释，不会永远 pending）。
 */
@Repository
public class TaskDao {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final JdbcTemplate jdbc;

    private static final RowMapper<TaskRecord> MAPPER = (rs, rowNum) -> new TaskRecord(
            rs.getString("id"), rs.getString("type"), rs.getString("status"),
            rs.getString("params_json"), rs.getString("steps_json"), rs.getString("result_json"),
            rs.getString("error_json"), rs.getString("callback_url"),
            rs.getInt("callback_attempts"), rs.getString("callback_last_code"),
            rs.getString("callback_last_at"), rs.getString("created_at"), rs.getString("updated_at"),
            rs.getString("finished_at"));

    public TaskDao(javax.sql.DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id            TEXT PRIMARY KEY,
                    type          TEXT NOT NULL,
                    status        TEXT NOT NULL,
                    params_json   TEXT NOT NULL,
                    steps_json    TEXT,
                    result_json   TEXT,
                    error_json    TEXT,
                    callback_url  TEXT,
                    callback_attempts INTEGER NOT NULL DEFAULT 0,
                    callback_last_code TEXT,
                    callback_last_at   TEXT,
                    created_at    TEXT NOT NULL,
                    updated_at    TEXT NOT NULL,
                    finished_at   TEXT
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tasks_created ON tasks(created_at DESC)");
    }

    private static String now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(TS);
    }

    public void create(String id, String type, String paramsJson, String callbackUrl) {
        jdbc.update("INSERT INTO tasks (id, type, status, params_json, callback_url, created_at, updated_at)"
                        + " VALUES (?, ?, 'pending', ?, ?, ?, ?)",
                id, type, paramsJson, emptyToNull(callbackUrl), now(), now());
    }

    public void markRunning(String id) {
        jdbc.update("UPDATE tasks SET status = 'running', updated_at = ? WHERE id = ?", now(), id);
    }

    public void updateSteps(String id, String stepsJson) {
        jdbc.update("UPDATE tasks SET steps_json = ?, updated_at = ? WHERE id = ?", stepsJson, now(), id);
    }

    public void finish(String id, String status, String resultJson, String errorJson, String stepsJson) {
        jdbc.update("UPDATE tasks SET status = ?, result_json = ?, error_json = ?, steps_json = ?,"
                        + " finished_at = ?, updated_at = ? WHERE id = ?",
                status, resultJson, errorJson, stepsJson, now(), now(), id);
    }

    public void webhookProgress(String id, int attempts, String lastCode) {
        jdbc.update("UPDATE tasks SET callback_attempts = ?, callback_last_code = ?, callback_last_at = ?,"
                        + " updated_at = ? WHERE id = ?", attempts, lastCode, now(), now(), id);
    }

    public Optional<TaskRecord> get(String id) {
        List<TaskRecord> rows = jdbc.query("SELECT * FROM tasks WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<TaskRecord> listRecent(int limit) {
        return jdbc.query("SELECT * FROM tasks ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    /** 启动恢复：把上次进程未跑完的任务标记为失败。返回受影响行数。 */
    public int recoverInterrupted() {
        String error = "{\"code\": \"INTERRUPTED\", \"message\": \"服务重启，任务中断\"}";
        return jdbc.update("UPDATE tasks SET status = 'error', error_json = ?, finished_at = ?, updated_at = ?"
                + " WHERE status IN ('pending', 'running')", error, now(), now());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
