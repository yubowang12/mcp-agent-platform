package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.TaskStatus;
import com.example.mcpdemo.agent.model.TraceStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级判定单元测试（纯单测）：任一步骤失败且仍有答案 → DEGRADED。
 */
class TaskDegradationTest {

    @Test
    void failedStepMarksTaskDegraded() {
        TaskContext ctx = new TaskContext("t1", "现在几点", 10);
        ctx.setFinalAnswer("现在时间是 10:00");
        ctx.getSteps().add(step(1, false, ToolCallRecorder.TOOL_ERROR));
        ctx.getSteps().add(step(2, true, null));

        TaskOrchestrator.markDegradedIfNeeded(ctx);

        assertEquals(TaskStatus.DEGRADED, ctx.getStatus());
        assertEquals(ToolCallRecorder.TOOL_ERROR, ctx.getErrorCode());
        assertEquals(List.of(1), ctx.getDegradedSteps());
        assertTrue(ctx.getErrorMessage().contains("第 1 步"), ctx.getErrorMessage());
    }

    @Test
    void allStepsOkStaysOk() {
        TaskContext ctx = new TaskContext("t2", "现在几点", 10);
        ctx.setFinalAnswer("10:00");
        ctx.getSteps().add(step(1, true, null));

        TaskOrchestrator.markDegradedIfNeeded(ctx);

        assertEquals(TaskStatus.OK, ctx.getStatus());
        assertNull(ctx.getErrorCode());
        assertTrue(ctx.getDegradedSteps().isEmpty());
    }

    @Test
    void timeoutStatusIsNotOverwritten() {
        TaskContext ctx = new TaskContext("t3", "现在几点", 10);
        ctx.setStatus(TaskStatus.TIMEOUT);
        ctx.getSteps().add(step(1, false, ToolCallRecorder.TOOL_TIMEOUT));

        TaskOrchestrator.markDegradedIfNeeded(ctx);

        assertEquals(TaskStatus.TIMEOUT, ctx.getStatus(), "TIMEOUT 已表达失败，不应被降级覆盖");
    }

    @Test
    void firstFailureCodeWins() {
        TaskContext ctx = new TaskContext("t4", "两步任务", 10);
        ctx.setFinalAnswer("部分结果");
        ctx.getSteps().add(step(1, false, ToolCallRecorder.TOOL_ERROR));
        ctx.getSteps().add(step(2, false, ToolCallRecorder.CIRCUIT_OPEN));

        TaskOrchestrator.markDegradedIfNeeded(ctx);

        assertEquals(ToolCallRecorder.TOOL_ERROR, ctx.getErrorCode(), "取第一个失败步骤的原因");
        assertEquals(List.of(1, 2), ctx.getDegradedSteps());
    }

    private static TraceStep step(int index, boolean ok, String errorCode) {
        return new TraceStep(index, "demotools_get_time", "demo-tools", "{}", ok,
                12L, ok ? "10:00" : "工具调用失败：boom", ok ? null : "boom", errorCode, 100L);
    }
}
