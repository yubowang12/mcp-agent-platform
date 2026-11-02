package com.example.mcpdemo.agent.model;

/**
 * 一次工具调用的追踪记录。
 *
 * <p>M2 中 steps 每项对应一次 tool_calls 的执行；M3 起追加 LLM 耗时等阶段信息；
 * M5 起追加 {@code errorCode}（{@code TOOL_TIMEOUT} / {@code TOOL_ERROR} / 后续的 {@code CIRCUIT_OPEN}），
 * 失败原因不再靠扫描错误文本推断。
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
