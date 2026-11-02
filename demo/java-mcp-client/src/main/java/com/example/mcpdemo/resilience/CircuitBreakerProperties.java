package com.example.mcpdemo.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 熔断配置，对应 {@code application.yml} 中的 {@code mcp.circuit.*}。
 *
 * <p>默认值：连续失败 3 次打开、冷却 30s、半开期只放 1 个探测请求。
 */
@ConfigurationProperties(prefix = "mcp.circuit")
public record CircuitBreakerProperties(
        /** 连续失败多少次打开熔断。 */
        @DefaultValue("3") int failureThreshold,
        /** 打开后的冷却时间（秒），到期允许一个探测请求。 */
        @DefaultValue("30") int openSeconds,
        /** 半开期允许并发的探测请求数。 */
        @DefaultValue("1") int halfOpenPermits) {
}
