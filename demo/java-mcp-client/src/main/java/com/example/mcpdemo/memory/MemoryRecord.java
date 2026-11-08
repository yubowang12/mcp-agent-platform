package com.example.mcpdemo.memory;

import java.time.Instant;

/**
 * 一条长期记忆（{@code memories} 表的行）。
 *
 * @param id          uuid
 * @param userId      归属用户（v1 缺省 {@code default}）
 * @param kind        semantic / episodic
 * @param text        记忆正文（注入 system 段、也用于人工查看）
 * @param embedding   float32 向量；第 2 步先写 null，第 3 步接 embedding 后填上
 * @param dim         向量维度（与配置的 {@code mcp.memory.embedding.dimensions} 对齐）
 * @param source      来源：{@code conversationId} / {@code messageId}
 * @param confidence  置信度（规则抽取的把握程度，便于以后按阈值清理）
 * @param createdAt   写入时间
 * @param expiresAt   过期时间；null 表示不过期（过期即不召回）
 */
public record MemoryRecord(
        String id,
        String userId,
        MemoryKind kind,
        String text,
        float[] embedding,
        Integer dim,
        String source,
        double confidence,
        Instant createdAt,
        Instant expiresAt) {

    /** 是否已过期（{@code expiresAt} 为空视为永不过期）。 */
    public boolean isExpired(Instant now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }
}
