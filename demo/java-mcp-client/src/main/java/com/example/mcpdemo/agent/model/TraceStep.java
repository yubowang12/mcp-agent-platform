package com.example.mcpdemo.agent.model;

/**
 * 一次工具调用的追踪记录。
 *
 * <p>M2 中 steps 每项对应一次 tool_calls 的执行；M3 起追加 LLM 耗时等阶段信息；
 * M5 起追加 {@code errorCode}（{@code TOOL_TIMEOUT} / {@code TOOL_ERROR} / 后续的 {@code CIRCUIT_OPEN}），
 * 失败原因不再靠扫描错误文本推断。
 *
 * @param step          第几步（从 1 开始，按执行顺序）
 * @param tool          工具名（前缀化之后，如 {@code demotools_get_time}）
 * @param serverKey     该工具所属的 MCP Server 名（{@code unknown} 表示没匹配到前缀）
 * @param args          模型给出的参数（JSON 字符串原文）
 * @param ok            本次调用是否成功
 * @param latencyMs     本次工具调用耗时（毫秒；被熔断拒绝时约等于 0）
 * @param resultPreview 结果预览（截断 200 字，便于看 trace 时快速判断）
 * @param error         失败时的原始错误文本；成功为 null
 * @param errorCode     失败原因码；成功为 null
 * @param llmLatencyMs  本轮为产出这次调用所花的 LLM 耗时（毫秒）
 */
public record TraceStep(
        int step,
        String tool,
        String serverKey,
        String args,
        boolean ok,
        long latencyMs,
        String resultPreview,
        String error,
        String errorCode,
        Long llmLatencyMs
) {
}
