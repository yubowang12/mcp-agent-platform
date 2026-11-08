package com.example.mcpdemo.memory;

import com.example.mcpdemo.memory.config.MemoryProperties;
import com.example.mcpdemo.memory.longterm.EmbeddingService;
import com.example.mcpdemo.memory.longterm.MemoryExtractor;
import com.example.mcpdemo.memory.longterm.MemoryPromptBuilder;
import com.example.mcpdemo.memory.longterm.MemoryRecallService;
import com.example.mcpdemo.memory.longterm.MemoryStore;
import com.example.mcpdemo.memory.model.MemoryCandidate;
import com.example.mcpdemo.memory.model.MemoryRecord;
import com.example.mcpdemo.memory.model.RecalledMemory;
import com.example.mcpdemo.memory.model.SessionSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 编排层访问记忆的唯一门面。
 *
 * <p>把"短期会话历史 + 长期记忆"收在一个类里，{@code TaskOrchestrator} 只依赖它，
 * 不直接碰 SQLite / ChatMemory / embedding：
 * <ul>
 *   <li>短期：{@link ChatMemory}（{@code MessageWindowChatMemory}，窗口上限见配置）；</li>
 *   <li>长期写入：{@link MemoryExtractor} 规则抽取 + {@link EmbeddingService} 转向量 + {@link MemoryStore} 落库；</li>
 *   <li>长期召回：{@link MemoryRecallService} 向量检索 + 时间衰减；{@link MemoryPromptBuilder} 拼 system 段。</li>
 * </ul>
 *
 * <p>**记忆故障不能拖垮任务**：所有异常在这里吞掉并记 warn，调用方拿到"空历史 / 没召回 / 没写成功"，
 * 与 trace 落盘的处理方式一致（辅助数据不阻断主流程）。
 */
@Slf4j
@Component
public class AgentMemory {

    /** 总开关：false 时所有方法立即返回空结果（配置 {@code mcp.memory.enabled}）。 */
    private final boolean enabled;
    /** 短期记忆（窗口裁剪后的会话消息）。 */
    private final ChatMemory chatMemory;
    /** 长期记忆存储（SQLite）。 */
    private final MemoryStore memoryStore;
    /** 规则抽取器（Host 规则写，不由模型写）。 */
    private final MemoryExtractor extractor;
    /** embedding 服务（写记忆时算向量）。 */
    private final EmbeddingService embeddingService;
    /** 召回服务（query 向量 + 余弦 + 时间衰减 + top-k）。 */
    private final MemoryRecallService recallService;
    /** system 段拼装器。 */
    private final MemoryPromptBuilder promptBuilder;

    @Autowired
    public AgentMemory(MemoryProperties props,
                       ObjectProvider<ChatMemory> chatMemoryProvider,
                       ObjectProvider<MemoryStore> memoryStoreProvider,
                       ObjectProvider<MemoryExtractor> extractorProvider,
                       ObjectProvider<EmbeddingService> embeddingServiceProvider,
                       ObjectProvider<MemoryRecallService> recallServiceProvider,
                       MemoryPromptBuilder promptBuilder) {
        this.enabled = props.enabled();
        this.chatMemory = chatMemoryProvider.getIfAvailable();
        this.memoryStore = memoryStoreProvider.getIfAvailable();
        this.extractor = extractorProvider.getIfAvailable();
        this.embeddingService = embeddingServiceProvider.getIfAvailable();
        this.recallService = recallServiceProvider.getIfAvailable();
        this.promptBuilder = promptBuilder;
        log.info("记忆层初始化: enabled={}, chatMemory={}, store={}, extractor={}, embedding={}, recall={}",
                enabled,
                chatMemory == null ? "null" : chatMemory.getClass().getSimpleName(),
                memoryStore == null ? "null" : memoryStore.getClass().getSimpleName(),
                extractor == null ? "null" : "on",
                embeddingService == null ? "null" : "on",
                recallService == null ? "null" : "on");
        if (!enabled) {
            log.info("记忆层已禁用（mcp.memory.enabled=false）：不加载历史、不召回、不写入");
        }
    }

    /**
     * 关闭记忆的实例（单测 / 旧调用方用）。
     *
     * <p>用带参数的私有构造代替真正的无参构造：本类有两个构造器，
     * 若存在一个真正无参的，Spring 会跳过 {@code @Autowired} 那个而回退到它。
     */
    private AgentMemory(boolean disabled) {
        this.enabled = false;
        this.chatMemory = null;
        this.memoryStore = null;
        this.extractor = null;
        this.embeddingService = null;
        this.recallService = null;
        this.promptBuilder = null;
    }

    public static AgentMemory disabled() {
        return new AgentMemory(true);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 读回同一会话的历史消息（短期记忆）。
     *
     * @param conversationId 会话 id（请求体 {@code sessionId}；缺省时由 Controller 生成一次性 id）
     */
    public List<Message> history(String conversationId) {
        if (!usable(conversationId)) {
            return List.of();
        }
        try {
            return chatMemory.get(conversationId);
        } catch (Exception e) {
            log.warn("读取会话历史失败（按无历史继续）: conversationId={}", conversationId, e);
            return List.of();
        }
    }

    /**
     * 会话结束后落一条"用户说了什么 + 助手答了什么"。
     *
     * <p>只落这两条：tool 消息缺少 id/name，反序列化不回工具调用链；中间推理过程属于 trace 的职责。
     */
    public void recordTurn(String conversationId, String userText, String assistantText) {
        if (!usable(conversationId)) {
            return;
        }
        try {
            List<Message> turn = new ArrayList<>(2);
            if (userText != null && !userText.isBlank()) {
                turn.add(new UserMessage(userText));
            }
            if (assistantText != null && !assistantText.isBlank()) {
                turn.add(new AssistantMessage(assistantText));
            }
            if (!turn.isEmpty()) {
                chatMemory.add(conversationId, turn);
            }
        } catch (Exception e) {
            log.warn("保存会话消息失败（不影响任务结果）: conversationId={}", conversationId, e);
        }
    }

    /**
     * 按 query 召回长期记忆（跨会话，按 userId 隔离）。
     *
     * @return 按最终分排序的召回结果；不可用时返回空列表
     */
    public List<RecalledMemory> recall(String userId, String query) {
        if (!enabled || recallService == null) {
            return List.of();
        }
        try {
            return recallService.recall(userId, query);
        } catch (Exception e) {
            log.warn("召回记忆失败（本次按无历史继续）: userId={}", userId, e);
            return List.of();
        }
    }

    /**
     * 把召回结果拼成 system 段；没有召回时返回空串。
     */
    public String promptSection(List<RecalledMemory> recalled) {
        if (!enabled || promptBuilder == null || recalled == null || recalled.isEmpty()) {
            return "";
        }
        try {
            return promptBuilder.build(recalled);
        } catch (Exception e) {
            log.warn("拼装记忆 prompt 失败（本次不注入）", e);
            return "";
        }
    }

    /**
     * 会话结束后按规则抽取长期记忆并落库（含向量）。
     *
     * @return 本次真正新增的记录（重复内容不会重复写，不会出现在返回值里）
     */
    public List<MemoryRecord> writeFromSession(SessionSummary session) {
        if (!enabled || memoryStore == null || extractor == null || session == null) {
            return List.of();
        }
        if (session.conversationId() == null || session.conversationId().isBlank()) {
            return List.of();
        }
        try {
            List<MemoryRecord> written = new ArrayList<>();
            for (MemoryCandidate candidate : extractor.extract(session)) {
                Instant expiresAt = candidate.ttlDays() == null
                        ? null
                        : Instant.now().plus(Duration.ofDays(candidate.ttlDays()));
                float[] embedding = embeddingService == null ? null : embeddingService.embed(candidate.text());
                memoryStore.add(session.userId(), candidate.kind(), candidate.text(), embedding,
                                "conversation:" + session.conversationId(), candidate.confidence(), expiresAt)
                        .ifPresent(written::add);
            }
            if (!written.isEmpty()) {
                log.info("本次会话写入 {} 条长期记忆: userId={}, conversationId={}",
                        written.size(), session.userId(), session.conversationId());
            }
            return written;
        } catch (Exception e) {
            log.warn("写长期记忆失败（不影响任务结果）: conversationId={}", session.conversationId(), e);
            return List.of();
        }
    }

    private boolean usable(String conversationId) {
        return enabled && chatMemory != null && conversationId != null && !conversationId.isBlank();
    }
}
