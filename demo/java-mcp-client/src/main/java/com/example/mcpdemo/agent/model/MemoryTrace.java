package com.example.mcpdemo.agent.model;

import java.util.List;

/**
 * trace 里的记忆片段（规格第 3 节：{@code memory: {recalled: [...], written: [...]}}）。
 *
 * <p>作用：**能解释为什么记得**——recalled 带 id/正文/分数，written 带 id/置信度/来源，
 * 出问题（记错、召回不准）可以直接从 trace 回溯，不用翻日志。
 *
 * @param conversationId 会话 id（请求体 sessionId；一次性会话是自动生成的）
 * @param userId         用户 id
 * @param recalled       本次召回的长期记忆
 * @param written        本次新写入的长期记忆
 * @param injectedChars  注入 system 段的字符数（上下文膨胀的度量，见规格第 11 节）
 */
public record MemoryTrace(
        String conversationId,
        String userId,
        List<RecalledRef> recalled,
        List<WrittenRef> written,
        int injectedChars) {

    /**
     * 一条召回记录。
     *
     * @param id     记忆 id（uuid，可用于查看/删除）
     * @param kind   {@code semantic} / {@code episodic}
     * @param text   记忆正文（trace 里截断 120 字）
     * @param score  最终分 = 余弦 × 时间衰减（排序用）
     * @param cosine 原始余弦相似度（解释"为什么召回它"）
     */
    public record RecalledRef(String id, String kind, String text, double score, double cosine) {
    }

    /**
     * 一条写入记录。
     *
     * @param id         记忆 id（uuid）
     * @param kind       {@code semantic} / {@code episodic}
     * @param text       记忆正文（trace 里截断 120 字）
     * @param confidence 置信度（规则抽取的把握程度）
     * @param source     来源，形如 {@code conversation:<conversationId>}
     */
    public record WrittenRef(String id, String kind, String text, Double confidence, String source) {
    }
}
