package com.example.mcpdemo.memory.model;

/**
 * 抽取器吐出的一条候选记忆（还没落库，没有 id / 向量）。
 *
 * @param kind       记忆类型
 * @param text       记忆正文
 * @param confidence 置信度
 * @param ttlDays    保留天数；null 表示不过期
 */
public record MemoryCandidate(
        MemoryKind kind,
        String text,
        double confidence,
        Integer ttlDays) {
}
