package com.example.mcpdemo.resilience;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断器状态机单元测试（纯单测，不依赖 Spring / MCP / Ollama）。
 *
 * <p>用可拨动的假时钟验证冷却与半开，不 sleep。
 */
class CircuitBreakerTest {

    /** 阈值 3、冷却 30s、半开并发 1。 */
    private static final CircuitBreakerProperties PROPS = new CircuitBreakerProperties(3, 30, 1);

    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker breaker = new CircuitBreaker("demo-tools", PROPS, clock, () -> {
    });

    @Test
    void threeConsecutiveFailuresOpenBreaker() {
        acquireAndFail(3);
        assertEquals(CircuitState.OPEN, breaker.state());
    }

    @Test
    void successResetsConsecutiveFailures() {
        acquireAndFail(2);
        assertTrue(breaker.tryAcquire());
        breaker.onSuccess();
        acquireAndFail(2);
        assertEquals(CircuitState.CLOSED, breaker.state(), "成功一次应把连续失败清零：放过偶发抖动，避免健康服务被误熔断");
    }

    @Test
    void openRejectsCalls() {
        acquireAndFail(3);
        assertFalse(breaker.tryAcquire(), "OPEN 期间应快速拒绝");
    }

    @Test
    void cooldownAllowsProbe() {
        acquireAndFail(3);
        clock.advance(Duration.ofSeconds(29));
        assertFalse(breaker.tryAcquire(), "冷却未到期不应放行");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(breaker.tryAcquire(), "冷却到期后应放行一个探测请求");
        assertEquals(CircuitState.HALF_OPEN, breaker.state());
    }

    @Test
    void probeSuccessClosesBreaker() {
        acquireAndFail(3);
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.tryAcquire());
        breaker.onSuccess();
        assertEquals(CircuitState.CLOSED, breaker.state());
        assertEquals(0, breaker.consecutiveFailures());
    }

    @Test
    void probeFailureReopensAndRestartsCooldown() {
        acquireAndFail(3);
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.tryAcquire());
        breaker.onFailure();
        assertEquals(CircuitState.OPEN, breaker.state());

        clock.advance(Duration.ofSeconds(29));
        assertFalse(breaker.tryAcquire(), "冷却应从探测失败时刻重新计时，而不是沿用旧计时");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void onlyOneProbeAllowedAtATime() {
        acquireAndFail(3);
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.tryAcquire());
        assertFalse(breaker.tryAcquire(), "半开并发额度为 1，第二个请求应被拒绝");
    }

    @Test
    void registryBumpsVersionOnStateChange() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(PROPS, clock);
        CircuitBreaker b = registry.get("demo-tools");
        long before = registry.version();

        for (int i = 0; i < 3; i++) {
            assertTrue(b.tryAcquire());
            b.onFailure();
        }

        assertTrue(registry.version() > before, "熔断器状态变化应让版本号递增（触发工具表重建）");
        assertSame(b, registry.get("demo-tools"), "同一个 server 应复用同一个熔断器");
    }

    private void acquireAndFail(int times) {
        for (int i = 0; i < times; i++) {
            assertTrue(breaker.tryAcquire(), "CLOSED 状态应放行");
            breaker.onFailure();
        }
    }

    /** 可拨动的假时钟（UTC）。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-31T00:00:00Z");

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
