package com.example.mcpdemo.agent;

import com.example.mcpdemo.resilience.CircuitBreaker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 熔断包装器：位于 {@link TimeoutToolCallback} 之外，是模型调用时最先碰到的一层。
 *
 * <p>职责只有三件：
 * <ol>
 *   <li>调用前 {@link CircuitBreaker#tryAcquire()}；拒绝就<b>毫秒级</b>返回错误文本，
 *       不再等 45s 超时，并把该步记成 {@link ToolCallRecorder#CIRCUIT_OPEN}；</li>
 *   <li>放行后调用内层（超时包装器 → 真正的 MCP 工具）；</li>
 *   <li>按内层记录的成功/失败喂给熔断器（超时、异常都算失败）。</li>
 * </ol>
 *
 * <p>{@link #getToolDefinition()} 原样透传：模型看到的工具名、描述、schema 不受包装影响。
 */
public class CircuitBreakingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final CircuitBreaker breaker;

    public CircuitBreakingToolCallback(ToolCallback delegate, CircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String toolName = getToolDefinition().name();

        if (!breaker.tryAcquire()) {
            long start = System.nanoTime();
            ToolCallRecorder.record(toolName, (System.nanoTime() - start) / 1_000_000,
                    false, ToolCallRecorder.CIRCUIT_OPEN);
            return "服务熔断中（" + breaker.serverKey() + " 连续失败），本次调用已快速拒绝，"
                    + "请稍后重试或改用其他工具。";
        }

        long start = System.nanoTime();
        int before = ToolCallRecorder.size();
        try {
            String result = delegate.call(toolInput);
            // 只认“本次调用之后新增的”记录：历史失败记录不能冒充本次结果
            ToolCallRecorder.CallRecord record = ToolCallRecorder.since(before, toolName);
            if (record != null && !record.ok()) {
                breaker.onFailure();
            } else {
                breaker.onSuccess();
            }
            return result;
        } catch (RuntimeException e) {
            // 兜底：如果内层没有包装超时器（理论上不会），异常也要计入失败并回填文本
            breaker.onFailure();
            ToolCallRecorder.record(toolName, (System.nanoTime() - start) / 1_000_000,
                    false, ToolCallRecorder.TOOL_ERROR);
            return "工具调用失败：" + e.getMessage();
        }
    }
}
