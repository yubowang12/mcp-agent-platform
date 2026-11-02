package com.example.mcpdemo.resilience;

import java.time.Clock;
import java.time.Instant;

/**
 * 单个 MCP Server 的熔断器（三态状态机，手写实现）。
 *
 * <p>规则（M5 决策记录）：
 * <ul>
 *   <li><b>CLOSED</b>：放行；连续失败达到 {@code failureThreshold} → OPEN；中间成功一次则失败计数清零
 *       （避免偶发抖动把健康服务误熔断；代价是"失败-成功-失败"交替的翻转服务永远凑不够阈值、
 *       不会被熔断——这是连续计数法的已知弱项，见类尾说明）；</li>
 *   <li><b>OPEN</b>：拒绝（快速失败）；冷却到期后由<b>下一个请求</b>触发 → HALF_OPEN；</li>
 *   <li><b>HALF_OPEN</b>：只放 {@code halfOpenPermits} 个探测请求；成功数达标 → CLOSED，
 *       任一失败 → OPEN 并<b>从此刻重新计时</b>冷却。</li>
 * </ul>
 *
 * <p>计数只在<b>状态切换</b>时清零（进入 HALF_OPEN、回到 CLOSED），探测期间不清零，
 * 否则永远攒不够恢复证据。
 *
 * <p>线程安全：所有公开方法 {@code synchronized}；同一 server 的所有工具共用一个实例。
 * 时间通过构造参数注入 {@link Clock}，测试可用假时钟拨时间，不必 sleep。
 */
public class CircuitBreaker {

    private final String serverKey;
    private final CircuitBreakerProperties props;
    private final Clock clock;
    private final Runnable onStateChange;

    private CircuitState state = CircuitState.CLOSED;
    /** CLOSED 期的连续失败次数；任何一次成功清零。 */
    private int consecutiveFailures;
    /** HALF_OPEN 期剩余放行额度（acquire 时扣减）。 */
    private int halfOpenPermits;
    /** HALF_OPEN 期已成功次数，达到 halfOpenPermits 才关闭。 */
    private int halfOpenSuccesses;
    /** OPEN 状态的冷却截止时间；仅 OPEN 时非空。 */
    private Instant openUntil;

    /**
     * @param serverKey     MCP Server 的键（如 demo-tools），用于日志与状态展示
     * @param props         熔断配置
     * @param clock         时钟（测试注入假时钟）
     * @param onStateChange 状态变化回调（registry 用它递增版本号，触发工具目录重建）
     */
    public CircuitBreaker(String serverKey, CircuitBreakerProperties props, Clock clock, Runnable onStateChange) {
        this.serverKey = serverKey;
        this.props = props;
        this.clock = clock;
        this.onStateChange = onStateChange;
    }

    /**
     * 申请一次调用许可。
     *
     * @return true 表示放行；false 表示熔断中，调用方应快速失败（不要再等超时）
     */
    public synchronized boolean tryAcquire() {

        // 当前是熔断状态
        if (state == CircuitState.OPEN) {
            // 冷却未到期
            if (Instant.now(clock).isBefore(openUntil)) {
                return false;
            }
            enterHalfOpen();
        }
        // 当前是半开状态
        if (state == CircuitState.HALF_OPEN) {
            // 探测额度用完 (并发限制)
            if (halfOpenPermits <= 0) {
                return false;
            }
            halfOpenPermits--;
        }
        return true;
    }

    /** 记录一次成功（探测成功可能触发 CLOSED）。 */
    public synchronized void onSuccess() {
        if (state == CircuitState.HALF_OPEN) {
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= props.halfOpenPermits()) {
                close();
            }
            return;
        }

        // 失败次数未超阈值, 本次成功, 重试连续失败次数
        if (state == CircuitState.CLOSED) {
            consecutiveFailures = 0;
        }
        // OPEN：迟到的成功不改变状态（服务仍按已熔断处理，等冷却探测）
    }

    /** 记录一次失败（可能是超时、异常、MCP isError）。 */
    public synchronized void onFailure() {
        Instant now = Instant.now(clock);

        // 半开状态, 如果发送的探测请求失败, 直接熔断
        if (state == CircuitState.HALF_OPEN) {
            open(now);
            return;
        }

        // 关闭状态, 累加失败次数, 达到阈值则熔断
        if (state == CircuitState.CLOSED) {
            consecutiveFailures++;
            if (consecutiveFailures >= props.failureThreshold()) {
                open(now);
            }
        }
    }

    public synchronized CircuitState state() {
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** 冷却截止时间；非 OPEN 状态返回 null。 */
    public synchronized Instant openUntil() {
        return openUntil;
    }

    public String serverKey() {
        return serverKey;
    }

    private void enterHalfOpen() {
        consecutiveFailures = 0;
        halfOpenSuccesses = 0;
        halfOpenPermits = props.halfOpenPermits();
        transitionTo(CircuitState.HALF_OPEN);
    }

    private void open(Instant now) {
        openUntil = now.plusSeconds(props.openSeconds());
        halfOpenPermits = 0;
        halfOpenSuccesses = 0;
        transitionTo(CircuitState.OPEN);
    }

    private void close() {
        consecutiveFailures = 0;
        halfOpenSuccesses = 0;
        halfOpenPermits = 0;
        openUntil = null;
        transitionTo(CircuitState.CLOSED);
    }

    private void transitionTo(CircuitState next) {
        if (state == next) {
            return;
        }
        state = next;
        if (onStateChange != null) {
            onStateChange.run();
        }
    }
}
