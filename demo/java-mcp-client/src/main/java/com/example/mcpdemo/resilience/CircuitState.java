package com.example.mcpdemo.resilience;

/**
 * 熔断器状态（三态）。
 *
 * <p>迁移规则见 {@link CircuitBreaker}：CLOSED →（连续失败达阈值）→ OPEN →
 * （冷却到期后的下一个请求）→ HALF_OPEN →（探测成功）→ CLOSED，探测失败则回到 OPEN。
 */
public enum CircuitState {

    /** 关闭：正常放行，统计连续失败次数。 */
    CLOSED,

    /** 打开：快速失败，冷却期内不放行任何请求。 */
    OPEN,

    /** 半开：只放行少量探测请求，用来判断服务是否恢复。 */
    HALF_OPEN
}
