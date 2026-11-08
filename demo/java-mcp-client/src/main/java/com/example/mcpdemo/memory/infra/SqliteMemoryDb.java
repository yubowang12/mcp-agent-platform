package com.example.mcpdemo.memory.infra;

import com.example.mcpdemo.memory.config.MemoryProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 记忆层 v1 的 SQLite 连接与建表（决策 1/2：SQLite 单文件、Java 内部访问、单写者）。
 *
 * <p>短期（{@code messages}）与长期（{@code memories}）**共用一个连接**：
 * SQLite 单文件并发写容易锁库，v1 直接串行化——所有使用方都用
 * {@code synchronized (db)} 包住整段 JDBC 操作。
 *
 * <p>建表是 {@code CREATE ... IF NOT EXISTS}，可重复执行；删掉 {@code memory.db}
 * 重启即等于"重建记忆"（A8）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqliteMemoryDb {

    /** 建表语句（幂等，可重复执行）；列含义见 {@code docs/04-memory-layer-v1.md} 第 4 节。 */
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS messages (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,  -- 自增主键，同时决定消息先后顺序
              conversation_id TEXT NOT NULL,                     -- 会话 id（请求体 sessionId；一次性会话为 s-<uuid>）
              role            TEXT NOT NULL,                     -- 消息角色：user / assistant（tool 不入库）
              content         TEXT NOT NULL,                     -- 消息正文
              created_at      TEXT NOT NULL                      -- 写入时间（ISO-8601 UTC）
            );
            CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, id);
            CREATE TABLE IF NOT EXISTS memories (
              id         TEXT PRIMARY KEY,            -- uuid
              user_id    TEXT NOT NULL,               -- 归属用户（v1 缺省 default）
              kind       TEXT NOT NULL,               -- semantic / episodic
              text       TEXT NOT NULL,               -- 记忆正文（注入 system 段、也用于人工查看）
              embedding  BLOB,                        -- float32 小端向量，长度 = dim * 4
              dim        INTEGER,                     -- 向量维度（与 mcp.memory.embedding.dimensions 对齐）
              source     TEXT,                        -- 来源：conversation:<conversationId>
              confidence REAL DEFAULT 0.8,            -- 置信度（规则抽取的把握程度）
              created_at TEXT NOT NULL,               -- 写入时间（定长毫秒格式，可按字典序比较）
              expires_at TEXT                         -- 过期时间；NULL 表示永不过期，过期即不召回
            );
            CREATE INDEX IF NOT EXISTS idx_memories_user ON memories(user_id, created_at);
            """;

    /** 共享的 SQLite 连接（单写者：短期与长期记忆共用，调用方用 {@code synchronized (db)} 串行化）。 */
    private final Connection connection;
    /** 数据库文件绝对路径（删掉它重启即"重建记忆"，A8）。 */
    private final Path dbFile;

    public SqliteMemoryDb(MemoryProperties props) {
        this.dbFile = Path.of(props.dbPath()).toAbsolutePath().normalize();
        try {
            Path parent = dbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement st = connection.createStatement()) {
                // WAL：为 v2 拆 MCP Server（多进程）预留；v1 单写者，开着也不吃亏
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                for (String ddl : SCHEMA.split(";")) {
                    if (!ddl.isBlank()) {
                        st.execute(ddl);
                    }
                }
            }
            log.info("记忆库就绪: {}（窗口={}，embedding={}x{}）", dbFile,
                    props.window().maxMessages(), props.embedding().model(), props.embedding().dimensions());
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("初始化 SQLite 记忆库失败: " + dbFile, e);
        }
    }

    /** 共享连接；调用方必须 {@code synchronized (db)} 包住整段操作。 */
    public Connection connection() {
        return connection;
    }

    public Path dbFile() {
        return dbFile;
    }

    /**
     * 关闭共享连接（bean 销毁时由 {@code @PreDestroy} 调用）。
     *
     * <p>拆包后测试在 {@code shortterm} / {@code longterm} 包，需要显式关闭连接
     * 来模拟进程重启，所以这里是 public，而不是原来的包级可见。
     */
    @PreDestroy
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("关闭记忆库连接失败: {}", dbFile, e);
        }
    }
}
