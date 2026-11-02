package com.example.mcpdemo.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 一次任务执行的完整追踪（也是 /api/task 的响应体）。
 *
 * <p>字段全量定义；M2 无法真实采集的字段先保留 null（tokenUsage / degraded / retryCount），
 * 由 M3/M5 填充。M5 起：任一步骤失败即 {@code status=DEGRADED}，{@code degraded=true}，
 * 失败步骤号记在 {@code degradedSteps}，原因码记在 {@code errorCode}。
 */
public record TaskTrace(
        String taskId,
        String task,
        TaskStatus status,
        String model,
        int maxSteps,
        int usedSteps,
        String startedAt,
        String finishedAt,
        long totalLatencyMs,
        String finalAnswer,
        List<TraceStep> steps,
        Boolean degraded,
        String errorCode,
        String errorMessage,
        List<Integer> degradedSteps,
        Map<String, Object> tokenUsage,
        Integer retryCount
) {
}
