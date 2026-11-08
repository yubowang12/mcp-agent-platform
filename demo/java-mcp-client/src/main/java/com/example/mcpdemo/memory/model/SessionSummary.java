package com.example.mcpdemo.memory.model;

import java.util.List;

/**
 * 一次会话结束后交给记忆层的原料（不直接依赖 {@code TaskContext}，便于单测）。
 *
 * @param conversationId 会话 id
 * @param userId         用户 id
 * @param task           用户这次说了什么
 * @param answer         助手最终答复
 * @param tools          本次调用过的工具名（按步骤顺序）
 * @param usedSteps      实际步数
 * @param success        是否 OK / DEGRADED（失败会话不写长期记忆）
 * @param oneOff         是否一次性会话（请求没带 sessionId）：一次性会话不写会话摘要
 */
public record SessionSummary(
        String conversationId,
        String userId,
        String task,
        String answer,
        List<String> tools,
        int usedSteps,
        boolean success,
        boolean oneOff) {
}
