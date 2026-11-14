package com.example.mcpdemo.agent;

import java.time.Instant;

/**
 * 当前任务的总时限（{@link ThreadLocal}）。
 *
 * <p>W1 的落点：循环入口已经用 deadline 判断是否继续，但单次 LLM / 工具调用
 * 仍用各自固定的超时，最坏情况会比 {@code task-timeout-seconds} 多等一个
 * LLM 超时 + 一个工具超时。这里把"还剩多少时间"传给下层的超时包装器，
 * 让每次调用最多只等到 deadline。
 *
 * <p>只在请求线程上设置；工具调用虽然提交到线程池执行，但预算在提交前读取，
 * 所以 ThreadLocal 足够，不需要额外传参。
 */
public final class TaskDeadline {

    private static final ThreadLocal<Long> DEADLINE_EPOCH_MS = new ThreadLocal<>();

    private TaskDeadline() {
    }

    /** 设置本次任务的硬截止时间（{@code run()} 入口调用）。 */
    public static void set(Instant deadline) {
        DEADLINE_EPOCH_MS.set(deadline.toEpochMilli());
    }

    /** 清理，必须放在 {@code finally}（线程池复用会带脏数据）。 */
    public static void clear() {
        DEADLINE_EPOCH_MS.remove();
    }

    /**
     * 剩余毫秒；未设置时返回 {@link Long#MAX_VALUE}（表示没有任务级约束，
     * 各层退回自己的固定超时）。
     */
    public static long remainingMillis() {
        Long deadline = DEADLINE_EPOCH_MS.get();
        return deadline == null ? Long.MAX_VALUE : deadline - System.currentTimeMillis();
    }

    /** 是否已用尽（未设置时永远为 false）。 */
    public static boolean isExpired() {
        return remainingMillis() <= 0;
    }
}
