package com.example.mcpdemo.agent;

import com.example.mcpdemo.resilience.CircuitBreaker;
import com.example.mcpdemo.resilience.CircuitBreakerProperties;
import com.example.mcpdemo.resilience.CircuitState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断包装器单元测试：拒绝路径、失败累计、成功清零、冷却后探测、定义透传。
 */
class CircuitBreakingToolCallbackTest {

    private static final CircuitBreakerProperties PROPS = new CircuitBreakerProperties(3, 30, 1);

    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker breaker = new CircuitBreaker("demo-tools", PROPS, clock, () -> {
    });
    private final AtomicInteger delegateCalls = new AtomicInteger();
    private final AtomicBoolean shouldFail = new AtomicBoolean(true);
    private final CircuitBreakingToolCallback callback =
            new CircuitBreakingToolCallback(delegate(), breaker);

    @AfterEach
    void clearRecords() {
        ToolCallRecorder.clear();
    }

    @Test
    void failuresOpenBreaker() {
        failTimes(3);
        assertEquals(CircuitState.OPEN, breaker.state());
    }

    @Test
    void openBreakerRejectsFastWithoutCallingDelegate() {
        failTimes(3);
        delegateCalls.set(0);

        long start = System.nanoTime();
        String output = callback.call("{}");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(output.contains("熔断"), output);
        assertEquals(0, delegateCalls.get(), "熔断打开时不应真正调用工具");
        assertTrue(elapsedMs < 100, "应快速失败，实测 " + elapsedMs + "ms");

        ToolCallRecorder.CallRecord record = ToolCallRecorder.last("demotools_add");
        assertNotNull(record);
        assertFalse(record.ok());
        assertEquals(ToolCallRecorder.CIRCUIT_OPEN, record.errorCode());
    }

    @Test
    void successResetsFailureCount() {
        failTimes(2);
        shouldFail.set(false);
        assertEquals("4.0", callback.call("{}"));
        shouldFail.set(true);
        failTimes(2);
        assertEquals(CircuitState.CLOSED, breaker.state(), "中间成功一次应清零：放过偶发抖动，避免健康服务被误熔断");
    }

    @Test
    void probeAfterCooldownClosesBreaker() {
        failTimes(3);
        assertEquals(CircuitState.OPEN, breaker.state());

        clock.advance(Duration.ofSeconds(30));
        shouldFail.set(false);
        assertEquals("4.0", callback.call("{}"));
        assertEquals(CircuitState.CLOSED, breaker.state());
    }

    @Test
    void toolDefinitionPassedThrough() {
        assertEquals("demotools_add", callback.getToolDefinition().name(), "包装器不应改变模型看到的工具名");
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            callback.call("{}");
        }
    }

    private ToolCallback delegate() {
        ToolDefinition definition = ToolDefinition.builder()
                .name("demotools_add")
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
                delegateCalls.incrementAndGet();
                if (shouldFail.get()) {
                    throw new IllegalStateException("boom");
                }
                return "4.0";
            }
        };
    }

    /** 可拨动的假时钟（UTC）。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
