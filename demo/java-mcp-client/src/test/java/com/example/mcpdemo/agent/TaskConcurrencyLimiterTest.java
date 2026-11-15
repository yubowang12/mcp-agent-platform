package com.example.mcpdemo.agent;

import com.example.mcpdemo.config.PlannerProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W2：入口并发闸门单测（纯单测，不起 Spring）。
 */
class TaskConcurrencyLimiterTest {

    private static TaskConcurrencyLimiter limiter(int maxConcurrent) {
        return new TaskConcurrencyLimiter(new PlannerProperties(
                "qwen-plus", 300, 60, 45, 8, 32, 16, 64, maxConcurrent));
    }

    @Test
    void admitsUpToCapacityThenRejectsImmediately() {
        TaskConcurrencyLimiter limiter = limiter(2);

        assertTrue(limiter.tryEnter());
        assertTrue(limiter.tryEnter());
        assertEquals(2, limiter.inFlight());
        assertEquals(2, limiter.capacity());

        long start = System.nanoTime();
        assertFalse(limiter.tryEnter(), "满了必须立即拒绝，不能阻塞等待");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 100, "拒绝耗时应远小于任务超时，实际 " + elapsedMs + "ms");
    }

    @Test
    void releaseFreesExactlyOneSlot() {
        TaskConcurrencyLimiter limiter = limiter(1);
        assertTrue(limiter.tryEnter());
        assertFalse(limiter.tryEnter());

        limiter.exit();
        assertEquals(0, limiter.inFlight());
        assertTrue(limiter.tryEnter(), "释放后应能再进一个");
    }

    @Test
    void capacityIsAtLeastOneEvenIfConfiguredZero() {
        TaskConcurrencyLimiter limiter = limiter(0);
        assertEquals(1, limiter.capacity(), "配置成 0 会把服务锁死，兜底成 1");
        assertTrue(limiter.tryEnter());
    }
}
