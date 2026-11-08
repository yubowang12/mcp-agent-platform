package com.example.mcpdemo.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 一次任务执行的完整追踪（也是 /api/task 的响应体）。
 *
 * <p>字段全量定义；M2 无法真实采集的字段先保留 null（tokenUsage / degraded / retryCount），
 * 由 M3/M5 填充。M5 起：任一步骤失败即 {@code status=DEGRADED}，{@code degraded=true}，
 * 失败步骤号记在 {@code degradedSteps}，原因码记在 {@code errorCode}。
 * 记忆层 v1 起：{@code memory} 记录本次召回/写入了哪些记忆（可解释"为什么记得"）。
 *
 * @param taskId         任务 id（{@code t-<8位uuid>}，每次请求生成）
 * @param task           用户原始任务文本
 * @param status         任务终态（OK / DEGRADED / MAX_STEPS / TIMEOUT / ERROR）
 * @param model          本次实际使用的规划模型名
 * @param maxSteps       请求允许的最大步数
 * @param usedSteps      实际执行步数（工具调用次数）
 * @param startedAt      开始时间（ISO-8601 UTC）
 * @param finishedAt     结束时间（ISO-8601 UTC）
 * @param totalLatencyMs 端到端总耗时（毫秒）
 * @param finalAnswer    最终答复（失败时为兜底文案）
 * @param steps          每一步的追踪明细，见 {@link TraceStep}
 * @param degraded       是否降级（{@code status=DEGRADED} 时为 true）
 * @param errorCode      失败原因码（如 {@code TOOL_TIMEOUT} / {@code EMPTY_ANSWER}），成功为 null
 * @param errorMessage   失败原因的可读说明，成功为 null
 * @param degradedSteps  降级的步骤号列表（空表示无降级步骤）
 * @param tokenUsage     token 用量；M2 未采集，恒为 null
 * @param retryCount     重试次数；M2 未实现，恒为 null
 * @param memory         记忆层片段：本次召回了什么、写入了什么、注入了多少字，见 {@link MemoryTrace}
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
        Integer retryCount,
        MemoryTrace memory
) {
}
