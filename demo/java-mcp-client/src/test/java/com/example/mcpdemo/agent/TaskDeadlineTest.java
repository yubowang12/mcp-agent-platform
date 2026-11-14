package com.example.mcpdemo.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1 单元测试：任务总时限如何收缩单次工具调用的预算。
 *
 * <p>纯单测，不需要 MCP / 模型 / Spring 容器。
 */
class TaskDeadlineTest {

    @AfterEach
    void cleanup() {
        TaskDeadline.clear();
        ToolCallRecorder.clear();
    }

    @Test
    void withoutDeadlineTheBudgetIsUnbounded() {
        assertEquals(Long.MAX_VALUE, TaskDeadline.remainingMillis());
        assertFalse(TaskDeadline.isExpired());
    }

    @Test
    void toolTimeoutIsShrunkToRemainingBudget() {
        TaskDeadline.set(Instant.now().plusMillis(400));
        TimeoutToolCallback callback = new TimeoutToolCallback(sleeping("slow", 3000), 45);

        long startedAt = System.currentTimeMillis();
        String output = callback.call("{}");
        long elapsed = System.currentTimeMillis() - startedAt;

        assertTrue(output.startsWith("工具调用超时"), output);
        assertTrue(elapsed < 2000, "应按剩余 400ms 截断，而不是等满 45s，实际 " + elapsed + "ms");

        ToolCallRecorder.CallRecord record = ToolCallRecorder.drain().get(0);
        assertFalse(record.ok());
        assertEquals(ToolCallRecorder.TOOL_TIMEOUT, record.errorCode());
    }

    @Test
    void expiredBudgetFailsFastWithoutInvokingTool() {
        TaskDeadline.set(Instant.now().minusMillis(1));
        AtomicBoolean invoked = new AtomicBoolean(false);
        TimeoutToolCallback callback = new TimeoutToolCallback(
                callback("never", input -> {
                    invoked.set(true);
                    return "ok";
                }), 45);

        String output = callback.call("{}");

        assertFalse(invoked.get(), "预算已用尽时不应再提交工具调用");
        assertTrue(output.startsWith("工具调用超时"), output);

        ToolCallRecorder.CallRecord record = ToolCallRecorder.drain().get(0);
        assertEquals(ToolCallRecorder.TOOL_TIMEOUT, record.errorCode());
    }

    @Test
    void successfulCallStillUsesConfiguredTimeoutWhenBudgetIsLarger() {
        TaskDeadline.set(Instant.now().plusSeconds(300));
        TimeoutToolCallback callback = new TimeoutToolCallback(returning("add", "4.0"), 5);

        assertEquals("4.0", callback.call("{}"));

        ToolCallRecorder.CallRecord record = ToolCallRecorder.drain().get(0);
        assertTrue(record.ok());
        assertNull(record.errorCode());
    }

    private static ToolCallback returning(String name, String result) {
        return callback(name, input -> result);
    }

    private static ToolCallback sleeping(String name, long millis) {
        return callback(name, input -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });
    }

    private static ToolCallback callback(String name, Function<String, String> body) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("测试用工具")
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return body.apply(toolInput);
            }
        };
    }
}
