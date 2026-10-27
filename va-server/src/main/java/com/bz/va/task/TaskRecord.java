package com.bz.va.task;

/** 任务表一行（JSON 字段为原始字符串，由控制器按需解析）。 */
public record TaskRecord(
        String id,
        String type,
        String status, // pending | running | done | error
        String paramsJson,
        String stepsJson,
        String resultJson,
        String errorJson,
        String callbackUrl,
        int callbackAttempts,
        String callbackLastCode,
        String callbackLastAt,
        String createdAt,
        String updatedAt,
        String finishedAt) {
}
