package com.example.mcpdemo.memory.shortterm;

import com.example.mcpdemo.memory.config.MemoryProperties;
import com.example.mcpdemo.memory.infra.SqliteMemoryDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 短期记忆（A2）：窗口裁剪 + 落库 + 重启后仍能读回。
 *
 * <p>纯单测：不连 Spring、不调模型；SQLite 文件放 JUnit 的临时目录。
 */
class SqliteChatMemoryRepositoryTest {

    @TempDir
    Path tempDir;

    /** 每个用例独立的临时 SQLite 库。 */
    private SqliteMemoryDb db;
    /** 被测仓库（短期消息落库）。 */
    private SqliteChatMemoryRepository repository;
    /** 被测窗口记忆（负责裁剪，仓库只按整段替换落库）。 */
    private ChatMemory chatMemory;

    @BeforeEach
    void setUp() {
        db = new SqliteMemoryDb(properties(4));
        repository = new SqliteChatMemoryRepository(db);
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(4)
                .build();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void windowKeepsOnlyLatestMessages() {
        chatMemory.add("c-1", List.of(new UserMessage("u1"), new AssistantMessage("a1")));
        chatMemory.add("c-1", List.of(new UserMessage("u2"), new AssistantMessage("a2")));
        chatMemory.add("c-1", List.of(new UserMessage("u3"), new AssistantMessage("a3")));

        List<Message> loaded = chatMemory.get("c-1");

        assertEquals(4, loaded.size(), "A2：窗口上限 4，只留最近 4 条");
        assertEquals("u2", loaded.get(0).getText());
        assertEquals("a3", loaded.get(3).getText());
        assertEquals(4, repository.findByConversationId("c-1").size(), "A2：库里也只剩窗口内的行");
    }

    @Test
    void historyIsReadBackInOrder() {
        chatMemory.add("c-2", List.of(new UserMessage("第一个问题"), new AssistantMessage("第一个回答")));
        chatMemory.add("c-2", List.of(new UserMessage("第二个问题"), new AssistantMessage("第二个回答")));

        List<Message> loaded = chatMemory.get("c-2");

        assertEquals(4, loaded.size());
        assertEquals("第一个问题", loaded.get(0).getText());
        assertEquals("第一个回答", loaded.get(1).getText());
        assertEquals("第二个问题", loaded.get(2).getText());
        assertEquals("第二个回答", loaded.get(3).getText());
        assertTrue(loaded.get(0) instanceof UserMessage);
        assertTrue(loaded.get(1) instanceof AssistantMessage);
    }

    @Test
    void sessionsAreIsolated() {
        chatMemory.add("c-a", List.of(new UserMessage("A 的问题")));
        chatMemory.add("c-b", List.of(new UserMessage("B 的问题")));

        assertEquals(List.of("A 的问题"), texts(chatMemory.get("c-a")));
        assertEquals(List.of("B 的问题"), texts(chatMemory.get("c-b")));
        assertEquals(2, repository.findConversationIds().size());
        assertTrue(repository.findConversationIds().containsAll(List.of("c-a", "c-b")));
    }

    @Test
    void deleteRemovesConversation() {
        chatMemory.add("c-del", List.of(new UserMessage("要删掉")));
        chatMemory.clear("c-del");

        assertTrue(chatMemory.get("c-del").isEmpty());
        assertFalse(repository.findConversationIds().contains("c-del"));
    }

    @Test
    void historySurvivesRestart() {
        chatMemory.add("c-persist", List.of(new UserMessage("重启前的消息"), new AssistantMessage("重启前的答复")));

        // 模拟进程重启：关掉连接，用同一个文件新建一个库 + 仓库
        db.close();
        SqliteMemoryDb reopened = new SqliteMemoryDb(properties(20));
        try {
            SqliteChatMemoryRepository newRepo = new SqliteChatMemoryRepository(reopened);
            List<Message> loaded = newRepo.findByConversationId("c-persist");
            assertEquals(List.of("重启前的消息", "重启前的答复"), texts(loaded));
        } finally {
            reopened.close();
        }
    }

    private List<String> texts(List<Message> messages) {
        List<String> out = new ArrayList<>();
        for (Message m : messages) {
            out.add(m.getText());
        }
        return out;
    }

    private MemoryProperties properties(int maxMessages) {
        return new MemoryProperties(
                true,
                tempDir.resolve("memory.db").toString(),
                new MemoryProperties.Window(maxMessages),
                new MemoryProperties.Recall(5, 0.35, 30),
                new MemoryProperties.Embedding(true, "text-embedding-v3", 1024),
                new MemoryProperties.Extract(true, 0.8, 90));
    }
}
