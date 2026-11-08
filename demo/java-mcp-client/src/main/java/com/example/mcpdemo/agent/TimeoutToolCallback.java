package com.example.mcpdemo.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 给单个工具调用加超时保护，并把结果记到 {@link ToolCallRecorder}。
 *
 * <p>失败判定只看<b>可判定的事实</b>，不再依赖错误文本前缀：
 * <ul>
 *   <li>超时：本包装器的 {@link TimeoutException} → {@link ToolCallRecorder#TOOL_TIMEOUT}；</li>
 *   <li>调用失败：Spring AI 的 {@code SyncMcpToolCallback} 在 MCP 返回 {@code isError=true}
 *       或传输异常时抛 {@code ToolExecutionException} → {@link ToolCallRecorder#TOOL_ERROR}。</li>
 * </ul>
 *
 * <p>为了不改变规划器的对话体验，失败仍以错误文本返回（模型可据此换工具或放弃）；
 * 结构化的成败信息走 {@link ToolCallRecorder} 旁路给编排器。
 *
 * <p>包装顺序：{@link CircuitBreakingToolCallback} 在外、本类在内——熔断器需要在
 * "真的调用了"时才知道成败，超时也算失败。
 */
public class TimeoutToolCallback implements ToolCallback {

    /** 超时隔离线程池：守护线程，卡住的工具调用不会拖住 JVM 退出。 */
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-timeout");
        t.setDaemon(true);
        return t;
    });

    /** 被包装的内层回调（通常是某个 MCP 工具）。 */
    private final ToolCallback delegate;
    /** 单次工具调用超时（秒），来自 {@code mcp.planner.tool-timeout-seconds}。 */
    private final long timeoutSeconds;

    public TimeoutToolCallback(ToolCallback delegate, long timeoutSeconds) {
        this.delegate = delegate;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        long start = System.nanoTime();
        Future<String> future = null;
        try {
            future = POOL.submit(() -> delegate.call(toolInput));
            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            record(toolName, start, true, null);
            return result;
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            record(toolName, start, false, ToolCallRecorder.TOOL_TIMEOUT);
            return "工具调用超时（" + timeoutSeconds + "s），未返回结果。";
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            record(toolName, start, false, ToolCallRecorder.TOOL_ERROR);
            return "工具调用失败：" + rootMessage(e);
        }
    }

    private static void record(String toolName, long start, boolean ok, String errorCode) {
        ToolCallRecorder.record(toolName, (System.nanoTime() - start) / 1_000_000, ok, errorCode);
    }

    /**
     * 取最内层异常的消息：线程池会把异常包成 {@code ExecutionException}，
     * Spring AI 又包一层 {@code ToolExecutionException}，直接用外层消息会很难读。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
