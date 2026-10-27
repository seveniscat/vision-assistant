package com.bz.va.service;

/** 流水线步骤回调：记录每个步骤的成功/失败与耗时（任务层据此持久化 steps）。 */
@FunctionalInterface
public interface StepListener {

    StepListener NOOP = (name, ok, elapsedMs, detail) -> {
    };

    /**
     * @param name      步骤名（如 ocr.analyze）
     * @param ok        是否成功
     * @param elapsedMs 耗时（毫秒）
     * @param detail    附加信息（如 "items=4"）；失败时为错误摘要
     */
    void onStep(String name, boolean ok, long elapsedMs, String detail);
}
