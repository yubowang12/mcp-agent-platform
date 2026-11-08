package com.example.mcpdemo.memory.model;

/**
 * 一条召回结果（trace 与 system 注入都用它）。
 *
 * @param memory  记忆本体
 * @param score   最终分：余弦 × 时间衰减（排序用）
 * @param cosine  原始余弦相似度（解释"为什么召回它"）
 * @param decay   时间衰减系数（0~1，越旧越小）
 */
public record RecalledMemory(
        MemoryRecord memory,
        double score,
        double cosine,
        double decay) {
}
