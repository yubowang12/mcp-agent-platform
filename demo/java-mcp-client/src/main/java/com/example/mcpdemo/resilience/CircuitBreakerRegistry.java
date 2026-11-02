package com.example.mcpdemo.resilience;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 MCP Server 管理熔断器（一个 server 一个熔断器，同 server 的所有工具共用）。
 *
 * <p>另外维护一个 {@code version}：任意熔断器状态变化时 +1。
 * 编排器据此判断"模型看到的工具表是否需要重建"——
 * 重建要调 {@code tools/list}（有网络开销），所以不能每次请求都重建。
 */
@Component
public class CircuitBreakerRegistry {

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private final CircuitBreakerProperties props;
    private final Clock clock;

    /** 生产用：系统时钟。 */
    @Autowired
    public CircuitBreakerRegistry(CircuitBreakerProperties props) {
        this(props, Clock.systemUTC());
    }

    /** 测试用：注入假时钟。 */
    CircuitBreakerRegistry(CircuitBreakerProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    /** 取（必要时创建）某个 server 的熔断器。 */
    public CircuitBreaker get(String serverKey) {
        return breakers.computeIfAbsent(serverKey,
                key -> new CircuitBreaker(key, props, clock, version::incrementAndGet));
    }

    /** 状态版本号：任意熔断器状态变化即递增。 */
    public long version() {
        return version.get();
    }

    /** 当前所有熔断器的只读快照（供 /api/servers 展示）。 */
    public Map<String, CircuitBreaker> snapshot() {
        return Map.copyOf(breakers);
    }
}
