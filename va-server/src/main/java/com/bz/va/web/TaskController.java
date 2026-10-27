package com.bz.va.web;

import com.bz.va.task.TaskManager;
import com.bz.va.task.TaskRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 异步任务查询：状态/步骤/结果（SQLite 持久化，重启后已完成结果仍可查）。 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskManager taskManager;
    private final ObjectMapper mapper;

    public TaskController(TaskManager taskManager, ObjectMapper mapper) {
        this.taskManager = taskManager;
        this.mapper = mapper;
    }

    @GetMapping("/tasks")
    public Map<String, Object> list(@RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(200, limit));
        return Map.of("tasks", taskManager.listRecent(bounded).stream()
                .map(this::summary).toList());
    }

    @GetMapping("/tasks/{taskId}")
    public Map<String, Object> get(@PathVariable String taskId) {
        TaskRecord task = taskManager.get(taskId)
                .orElseThrow(() -> new ApiException("NOT_FOUND", 404, "任务不存在: " + taskId));
        return detail(task);
    }

    private Map<String, Object> summary(TaskRecord task) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("task_id", task.id());
        node.put("recipe", task.type());
        node.put("status", task.status());
        node.put("created_at", task.createdAt());
        node.put("finished_at", task.finishedAt());
        node.put("callback_attempts", task.callbackAttempts());
        return node;
    }

    private Map<String, Object> detail(TaskRecord task) {
        ObjectNode node = mapper.createObjectNode();
        node.put("task_id", task.id());
        node.put("recipe", task.type());
        node.put("status", task.status());
        node.set("params", parse(task.paramsJson()));
        node.set("steps", parse(task.stepsJson()));
        node.set("result", parse(task.resultJson()));
        node.set("error", parse(task.errorJson()));
        ObjectNode callback = node.putObject("callback");
        callback.put("url", task.callbackUrl());
        callback.put("attempts", task.callbackAttempts());
        callback.put("last_code", task.callbackLastCode());
        callback.put("last_at", task.callbackLastAt());
        node.put("created_at", task.createdAt());
        node.put("updated_at", task.updatedAt());
        node.put("finished_at", task.finishedAt());
        return mapper.convertValue(node, Map.class);
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(json);
        }
    }
}
