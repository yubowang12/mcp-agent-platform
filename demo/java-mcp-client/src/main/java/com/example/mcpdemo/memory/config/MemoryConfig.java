package com.example.mcpdemo.memory.config;

import com.example.mcpdemo.memory.shortterm.SqliteChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短期记忆装配：{@link MessageWindowChatMemory} + SQLite Repository。
 *
 * <p>窗口裁剪（只留最近 N 条）由 {@code MessageWindowChatMemory} 负责，
 * Repository 只按"整段替换"落库，所以库里行数天然 ≤ 窗口上限。
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryConfig {

    @Bean
    public ChatMemory chatMemory(SqliteChatMemoryRepository repository, MemoryProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.window().maxMessages())
                .build();
    }
}
