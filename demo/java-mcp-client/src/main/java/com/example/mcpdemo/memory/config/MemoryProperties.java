package com.example.mcpdemo.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 记忆层 v1 配置（{@code application.yml} 的 {@code mcp.memory.*}）。
 *
 * <p>规格：{@code docs/04-memory-layer-v1.md}。所有阈值都放配置，代码里不写死。
 *
 * <p>embedding 的模型名与维度来自 2026-11-07 的实测（见 {@code docs/STATUS.md} 日志）：
 * DashScope {@code /compatible-mode/v1/embeddings} 对 {@code text-embedding-v3} 默认返回 1024 维，
 * 且支持 {@code dimensions} 参数（768 实测通过）。换模型只改配置。
 */
@ConfigurationProperties(prefix = "mcp.memory")
public record MemoryProperties(
        /** 总开关：关掉则不建库、不注入历史、不召回、不写记忆。 */
        @DefaultValue("true") boolean enabled,
        /** SQLite 单文件路径（相对路径按进程工作目录解析）。 */
        @DefaultValue("demo/logs/memory/memory.db") String dbPath,
        /** 短期记忆：会话消息窗口上限（A2）。 */
        @DefaultValue Window window,
        /** 长期记忆：召回参数。 */
        @DefaultValue Recall recall,
        /** embedding 模型（与 RAG 共用）。 */
        @DefaultValue Embedding embedding,
        /** 长期记忆写入（规则抽取）参数。 */
        @DefaultValue Extract extract) {

    /** 短期记忆窗口。 */
    public record Window(
            /** 每个会话最多保留的消息条数（超出丢弃最旧的）。 */
            @DefaultValue("20") int maxMessages) {
    }

    /** 召回参数：top-k、余弦下限、时间衰减半衰期。 */
    public record Recall(
            /** 每次最多注入多少条记忆（控制上下文膨胀）。 */
            @DefaultValue("5") int topK,
            /** 余弦相似度下限：低于它的记忆不注入（宁缺毋滥）。 */
            @DefaultValue("0.35") double minScore,
            /** 时间衰减半衰期（天）：{@code score = 0.5^(age/halfLife)}，0 表示不衰减。 */
            @DefaultValue("30") double halfLifeDays) {
    }

    /** embedding 模型与维度（维度写进配置，不在代码里写死 1024）。 */
    public record Embedding(
            /** 未过期时是否调用 embedding；关掉则只写不召回。 */
            @DefaultValue("true") boolean enabled,
            /** embedding 模型名（与 RAG 共用）。 */
            @DefaultValue("text-embedding-v3") String model,
            /** 向量维度；实测 text-embedding-v3 默认 1024。 */
            @DefaultValue("1024") int dimensions) {
    }

    /** 规则抽取参数：默认置信度、会话摘要（episodic）的保留天数。 */
    public record Extract(
            /** 是否启用长期记忆抽取；关掉则只做短期会话记忆。 */
            @DefaultValue("true") boolean enabled,
            /** 普通规则命中的置信度（"记住…"这类明确表达会在此基础上 +0.15）。 */
            @DefaultValue("0.8") double defaultConfidence,
            /** 会话摘要（episodic）的保留天数，到期后不再召回。 */
            @DefaultValue("90") int episodicTtlDays) {
    }
}
