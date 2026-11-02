package com.example.mcpdemo.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具调用结果记录器（请求线程内的旁路记录）。
 *
 * <p>为什么需要它：{@code ToolCallback} 只能返回字符串，成功/失败/被熔断三种情况在字符串里
 * 分不开。包装器在调用过程中把结构化结果记到当前线程，编排器执行完一轮后统一取走写 trace，
 * 这样既不改变"错误文本回填给规划器"的既有行为，又能拿到可判定的成败事实。
 *
 * <p>线程模型：{@code call()} 在请求线程上执行，记录写进请求线程的 ThreadLocal；
 * 每轮开始前编排器调用 {@link #clear()}，结束后 {@link #drain()}。
 */
public final class ToolCallRecorder {

    /** 单次工具调用超时（超时包装器产生）。 */
    public static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";

    /** 工具调用失败（MCP isError=true 或传输异常）。 */
    public static final String TOOL_ERROR = "TOOL_ERROR";

    /** 被熔断器拦截（未真正调用工具）。 */
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    /** 一次工具调用的结果；{@code errorCode} 为 null 表示成功。 */
    public record CallRecord(String toolName, long latencyMs, boolean ok, String errorCode) {
    }

    private static final ThreadLocal<List<CallRecord>> RECORDS =
            ThreadLocal.withInitial(ArrayList::new);

    private ToolCallRecorder() {
    }

    public static void record(String toolName, long latencyMs, boolean ok, String errorCode) {
        RECORDS.get().add(new CallRecord(toolName, latencyMs, ok, errorCode));
    }

    /** 取走当前线程的全部记录（取走即清空）。 */
    public static List<CallRecord> drain() {
        List<CallRecord> records = new ArrayList<>(RECORDS.get());
        RECORDS.get().clear();
        return records;
    }

    /** 只清空、不取走（异常路径兜底）。 */
    public static void clear() {
        RECORDS.get().clear();
    }

    /**
     * 取某个工具最近一条记录（不清空）。仅用于展示/断言，
     * <b>不要</b>用它判断"刚才这次调用"的成败——历史记录会冒充本次结果。
     */
    public static CallRecord last(String toolName) {
        List<CallRecord> records = RECORDS.get();
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).toolName().equals(toolName)) {
                return records.get(i);
            }
        }
        return null;
    }

    /** 当前线程已记录条数（调用前拍个快照，用于判断"这次调用之后有没有新记录"）。 */
    public static int size() {
        return RECORDS.get().size();
    }

    /**
     * 从 {@code fromIndex} 之后找该工具的第一条记录；没有则返回 null。
     *
     * <p>熔断包装器用它判断内层这次调用到底是成功还是失败——只认"调用之后新增的"记录，
     * 避免上一轮的历史记录冒充本次结果。
     */
    public static CallRecord since(int fromIndex, String toolName) {
        List<CallRecord> records = RECORDS.get();
        for (int i = Math.max(fromIndex, 0); i < records.size(); i++) {
            if (records.get(i).toolName().equals(toolName)) {
                return records.get(i);
            }
        }
        return null;
    }
}
