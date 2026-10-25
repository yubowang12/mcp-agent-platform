package com.example.mcpdemo.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 给单个工具调用加 45s 超时保护，并记录每次调用的耗时（供 trace 使用）。
 *
 * <p>超时/异常不向上抛出，而是返回错误文本——由模型决定换工具或放弃，
 * 同时编排器把该步记为失败（ok=false）。
 *
 * <p>计时说明：{@link #call} 运行在请求线程，耗时记入请求线程的 ThreadLocal；
 * 真正执行工具的是线程池中的工作线程。编排器每轮开始前需 {@link #drain()} 清空，
 * 避免同一线程处理多个请求时残留旧数据。
 */
public class TimeoutToolCallback implements ToolCallback {

    /** 单次工具调用的耗时采样（toolName 为给模型看的前缀化名字）。 */
    public record LatencySample(String toolName, long latencyMs) {
    }

    /** 请求线程内暂存本轮工具调用耗时。 */
    private static final ThreadLocal<List<LatencySample>> LATENCIES =
            ThreadLocal.withInitial(ArrayList::new);

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-timeout");
        t.setDaemon(true);
        return t;
    });

    private final ToolCallback delegate;
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
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            return "工具调用超时（" + timeoutSeconds + "s），未返回结果。";
        } catch (Exception e) {
            if (future != null) {
                future.cancel(true);
            }
            return "工具调用失败：" + e.getMessage();
        } finally {
            LATENCIES.get().add(new LatencySample(
                    toolName, (System.nanoTime() - start) / 1_000_000));
        }
    }

    /** 取走当前线程已记录的全部耗时样本（取走即清空）。 */
    public static List<LatencySample> drain() {
        List<LatencySample> samples = new ArrayList<>(LATENCIES.get());
        LATENCIES.get().clear();
        return samples;
    }

    /** 只清空当前线程的耗时样本（异常路径兜底）。 */
    public static void clear() {
        LATENCIES.get().clear();
    }
}
