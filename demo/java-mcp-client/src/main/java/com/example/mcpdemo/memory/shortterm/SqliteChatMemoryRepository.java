package com.example.mcpdemo.memory.shortterm;
import com.example.mcpdemo.memory.infra.SqliteMemoryDb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 短期记忆的 SQLite 实现（规格第 5 节）。
 *
 * <p>{@link ChatMemoryRepository} 契约：{@code saveAll} 传入的是**该会话的完整消息列表**
 * （{@code MessageWindowChatMemory} 已经做完窗口裁剪），所以这里按"先删后插"整段替换，
 * 数据库里的行数不会超出窗口上限——这就是验收 A2 的落点。
 *
 * <p>只持久化 user / assistant 两类消息：tool 消息缺少 {@code id/name}，
 * 反序列化不回 {@code ToolResponseMessage}，回放会破坏工具调用链；system 由代码生成，不入库。
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqliteChatMemoryRepository implements ChatMemoryRepository {

    /** {@code messages.role} 的取值：用户消息。 */
    private static final String ROLE_USER = "user";
    /** {@code messages.role} 的取值：助手消息。 */
    private static final String ROLE_ASSISTANT = "assistant";

    /** 共享的 SQLite 连接（单写者，所有操作 {@code synchronized (db)}）。 */
    private final SqliteMemoryDb db;

    public SqliteChatMemoryRepository(SqliteMemoryDb db) {
        this.db = db;
    }

    @Override
    public List<String> findConversationIds() {
        synchronized (db) {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT DISTINCT conversation_id FROM messages ORDER BY conversation_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("查询会话列表失败", e);
            }
            return ids;
        }
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        synchronized (db) {
            List<Message> messages = new ArrayList<>();
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT role, content FROM messages WHERE conversation_id = ? ORDER BY id")) {
                ps.setString(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString(1);
                        String content = rs.getString(2);
                        if (content == null || content.isBlank()) {
                            continue;
                        }
                        if (ROLE_USER.equals(role)) {
                            messages.add(new UserMessage(content));
                        } else if (ROLE_ASSISTANT.equals(role)) {
                            messages.add(new AssistantMessage(content));
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取会话消息失败: " + conversationId, e);
            }
            return messages;
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        synchronized (db) {
            try {
                conn().setAutoCommit(false);
                try (PreparedStatement del = conn().prepareStatement(
                        "DELETE FROM messages WHERE conversation_id = ?")) {
                    del.setString(1, conversationId);
                    del.executeUpdate();
                }
                String now = Instant.now().toString();
                try (PreparedStatement ins = conn().prepareStatement(
                        "INSERT INTO messages(conversation_id, role, content, created_at) VALUES (?, ?, ?, ?)")) {
                    for (Message message : messages) {
                        String role = roleOf(message);
                        String content = message.getText();
                        if (role == null || content == null || content.isBlank()) {
                            log.debug("跳过不落库的消息: conversationId={}, type={}", conversationId,
                                    message.getMessageType());
                            continue;
                        }
                        ins.setString(1, conversationId);
                        ins.setString(2, role);
                        ins.setString(3, content);
                        ins.setString(4, now);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn().commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new IllegalStateException("保存会话消息失败: " + conversationId, e);
            } finally {
                restoreAutoCommit();
            }
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        synchronized (db) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "DELETE FROM messages WHERE conversation_id = ?")) {
                ps.setString(1, conversationId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("删除会话失败: " + conversationId, e);
            }
        }
    }

    private static String roleOf(Message message) {
        MessageType type = message.getMessageType();
        if (type == MessageType.USER) {
            return ROLE_USER;
        }
        if (type == MessageType.ASSISTANT) {
            return ROLE_ASSISTANT;
        }
        return null;
    }

    private Connection conn() throws SQLException {
        return db.connection();
    }

    private void rollbackQuietly() {
        try {
            db.connection().rollback();
        } catch (SQLException ignored) {
            // 回滚失败不覆盖原始异常
        }
    }

    private void restoreAutoCommit() {
        try {
            db.connection().setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("恢复 autoCommit 失败", e);
        }
    }
}
