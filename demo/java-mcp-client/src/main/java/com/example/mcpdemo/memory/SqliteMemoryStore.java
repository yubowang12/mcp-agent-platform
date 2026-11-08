package com.example.mcpdemo.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 长期记忆的 SQLite 实现。
 *
 * <p>时间统一用**定长毫秒格式**（{@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}）存字符串，
 * 这样 SQL 里也能安全地按字典序比较过期时间（变长的 {@code Instant.toString()} 会出错）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqliteMemoryStore implements MemoryStore {

    /** 定长毫秒时间格式：保证 SQL 里按字典序比较过期时间也正确。 */
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 共享的 SQLite 连接（单写者，所有操作 {@code synchronized (db)}）。 */
    private final SqliteMemoryDb db;

    public SqliteMemoryStore(SqliteMemoryDb db) {
        this.db = db;
    }

    @Override
    public Optional<MemoryRecord> add(String userId, MemoryKind kind, String text, float[] embedding,
                                      String source, double confidence, Instant expiresAt) {
        if (userId == null || userId.isBlank() || kind == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        synchronized (db) {
            try {
                // 同一用户的同一条正文不重复写（重复说"记住…"不该刷出一堆记忆）
                try (PreparedStatement dup = db.connection().prepareStatement(
                        "SELECT id FROM memories WHERE user_id = ? AND text = ? LIMIT 1")) {
                    dup.setString(1, userId);
                    dup.setString(2, text);
                    try (ResultSet rs = dup.executeQuery()) {
                        if (rs.next()) {
                            log.debug("记忆已存在，跳过: userId={}, id={}", userId, rs.getString(1));
                            return Optional.empty();
                        }
                    }
                }

                MemoryRecord record = new MemoryRecord(
                        UUID.randomUUID().toString(),
                        userId,
                        kind,
                        text,
                        embedding,
                        embedding == null ? null : embedding.length,
                        source,
                        confidence,
                        Instant.now(),
                        expiresAt);

                try (PreparedStatement ins = db.connection().prepareStatement(
                        "INSERT INTO memories(id, user_id, kind, text, embedding, dim, source, confidence, created_at, expires_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ins.setString(1, record.id());
                    ins.setString(2, record.userId());
                    ins.setString(3, record.kind().value());
                    ins.setString(4, record.text());
                    if (record.embedding() == null) {
                        ins.setNull(5, Types.BLOB);
                        ins.setNull(6, Types.INTEGER);
                    } else {
                        ins.setBytes(5, MemoryVectors.toBlob(record.embedding()));
                        ins.setInt(6, record.dim());
                    }
                    ins.setString(7, record.source());
                    ins.setDouble(8, record.confidence());
                    ins.setString(9, TS.format(record.createdAt()));
                    if (record.expiresAt() == null) {
                        ins.setNull(10, Types.VARCHAR);
                    } else {
                        ins.setString(10, TS.format(record.expiresAt()));
                    }
                    ins.executeUpdate();
                }
                log.info("写入记忆: userId={}, kind={}, confidence={}, text={}", userId, kind.value(),
                        confidence, abbreviate(text));
                return Optional.of(record);
            } catch (SQLException e) {
                throw new IllegalStateException("写入记忆失败", e);
            }
        }
    }

    @Override
    public List<MemoryRecord> list(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        synchronized (db) {
            List<MemoryRecord> rows = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT id, user_id, kind, text, embedding, dim, source, confidence, created_at, expires_at"
                            + " FROM memories WHERE user_id = ? ORDER BY created_at DESC, id")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(map(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取记忆失败: " + userId, e);
            }
            return rows;
        }
    }

    @Override
    public List<MemoryRecord> findActive(String userId, Instant now) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        String cutoff = TS.format(now == null ? Instant.now() : now);
        synchronized (db) {
            List<MemoryRecord> rows = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT id, user_id, kind, text, embedding, dim, source, confidence, created_at, expires_at"
                            + " FROM memories WHERE user_id = ? AND (expires_at IS NULL OR expires_at >= ?)"
                            + " ORDER BY created_at DESC, id")) {
                ps.setString(1, userId);
                ps.setString(2, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(map(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取未过期记忆失败: " + userId, e);
            }
            return rows;
        }
    }

    @Override
    public boolean forget(String userId, String id) {
        if (userId == null || id == null) {
            return false;
        }
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "DELETE FROM memories WHERE user_id = ? AND id = ?")) {
                ps.setString(1, userId);
                ps.setString(2, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("删除记忆失败: " + id, e);
            }
        }
    }

    @Override
    public int purgeExpired(Instant now) {
        String cutoff = TS.format(now == null ? Instant.now() : now);
        synchronized (db) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "DELETE FROM memories WHERE expires_at IS NOT NULL AND expires_at < ?")) {
                ps.setString(1, cutoff);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("清理过期记忆失败", e);
            }
        }
    }

    private MemoryRecord map(ResultSet rs) throws SQLException {
        byte[] blob = rs.getBytes("embedding");
        int dimValue = rs.getInt("dim");
        Integer dim = rs.wasNull() ? null : dimValue;
        String expires = rs.getString("expires_at");
        return new MemoryRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                MemoryKind.from(rs.getString("kind")),
                rs.getString("text"),
                MemoryVectors.fromBlob(blob),
                dim,
                rs.getString("source"),
                rs.getDouble("confidence"),
                Instant.parse(rs.getString("created_at")),
                expires == null ? null : Instant.parse(expires));
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }
}
