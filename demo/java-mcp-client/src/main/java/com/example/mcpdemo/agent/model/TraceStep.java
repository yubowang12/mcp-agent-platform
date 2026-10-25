package com.example.mcpdemo.agent.model;

/**
 * 一次工具调用的追踪记录。
 *
 * <p>M2 中 steps 每项对应一次 tool_calls 的执行；M3 起追加 LLM 耗时等阶段信息。
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
        Long llmLatencyMs
) {
}
