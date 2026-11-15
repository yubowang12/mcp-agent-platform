package com.example.mcpdemo.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时包装器单元测试：成功 / 异常 / 超时 三种结果如何被记录（纯单测）。
 *
 * <p>这是 M5 "失败判定"的核心：成败来自事实（异常类型），不再依赖错误文本前缀。
 */
class TimeoutToolCallbackTest {

    @AfterEach
    void clearRecords() {
        ToolCallRecorder.clear();
    }

    @Test
    void successIsRecordedAsOk() {
        TimeoutToolCallback callback = new TimeoutToolCallback(returning("add", "4.0"), 5);

        assertEquals("4.0", callback.call("{}"));

        List<ToolCallRecorder.CallRecord> records = ToolCallRecorder.drain();
        assertEquals(1, records.size());
        assertTrue(records.get(0).ok());
        assertNull(records.get(0).errorCode());
        assertEquals("add", records.get(0).toolName());
    }

    @Test
    void exceptionIsRecordedAsToolError() {
        TimeoutToolCallback callback = new TimeoutToolCallback(throwing("add", "boom"), 5);

        String output = callback.call("{}");

        assertTrue(output.startsWith("工具调用失败"), "失败仍以文本返回给规划器，便于它换工具或放弃：" + output);
        assertTrue(output.contains("boom"), "错误文本应取最内层异常消息，而不是包装类的 toString：" + output);

        ToolCallRecorder.CallRecord record = ToolCallRecorder.drain().get(0);
        assertFalse(record.ok());
        assertEquals(ToolCallRecorder.TOOL_ERROR, record.errorCode());
    }

    @Test
    void timeoutIsRecordedAsToolTimeout() {
        TimeoutToolCallback callback = new TimeoutToolCallback(sleeping("slow", 1500), 1);

        String output = callback.call("{}");

        assertTrue(output.startsWith("工具调用超时"), output);

        ToolCallRecorder.CallRecord record = ToolCallRecorder.drain().get(0);
        assertFalse(record.ok());
        assertEquals(ToolCallRecorder.TOOL_TIMEOUT, record.errorCode());
    }

    @Test
    void saturatedPoolReturnsBusyImmediately() throws Exception {
        // 单线程 + SynchronousQueue：线程被占住后，第二次提交必定被 AbortPolicy 拒绝
        ExecutorService oneSlot = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        try {
            oneSlot.submit(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            TimeoutToolCallback callback = new TimeoutToolCallback(returning("add", "4.0"), 5, oneSlot);

            long start = System.nanoTime();
            String output = callback.call("{}");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(output.contains("系统繁忙"), output);
            assertTrue(elapsedMs < 100, "池满应立刻返回，实际 " + elapsedMs + "ms");

            ToolCallRecorder.CallRecord record = ToolCallRecorder.drain().get(0);
            assertFalse(record.ok());
            assertEquals(ToolCallRecorder.TOOL_ERROR, record.errorCode());
        } finally {
            oneSlot.shutdownNow();
        }
    }

    private static ToolCallback returning(String name, String result) {
        return callback(name, input -> result);
    }

    private static ToolCallback throwing(String name, String message) {
        return callback(name, input -> {
            throw new IllegalStateException(message);
        });
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
