package com.example.mcpdemo.memory.longterm;

import com.example.mcpdemo.memory.config.MemoryProperties;
import com.example.mcpdemo.memory.infra.SqliteMemoryDb;
import com.example.mcpdemo.memory.model.MemoryKind;
import com.example.mcpdemo.memory.model.MemoryRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆写入 / 查看 / 删除 / 过期清理（A3、A6、A8 的存储侧）。
 */
@SpringBootTest
class SqliteMemoryStoreTest {

    @TempDir
    Path tempDir;

    /** 每个用例独立的临时 SQLite 库。 */
    private SqliteMemoryDb db;
    /** 被测存储（长期记忆的增删查与过期清理）。 */
    private SqliteMemoryStore store;

    @BeforeEach
    void setUp() {
        db = new SqliteMemoryDb(properties());
        store = new SqliteMemoryStore(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void addKeepsSourceAndConfidence() {
        Optional<MemoryRecord> added = store.add("u-1", MemoryKind.SEMANTIC, "我喜欢用 Java 写后端",
                null, "conversation:c-1", 0.95, null);

        assertTrue(added.isPresent());
        MemoryRecord record = added.get();
        assertNotNull(record.id());
        assertEquals("u-1", record.userId());
        assertEquals(MemoryKind.SEMANTIC, record.kind());
        assertEquals("conversation:c-1", record.source(), "A3：记忆要带来源");
        assertEquals(0.95, record.confidence(), "A3：记忆要带置信度");

        List<MemoryRecord> listed = store.list("u-1");
        assertEquals(1, listed.size());
        assertEquals("我喜欢用 Java 写后端", listed.get(0).text());
    }

    @Test
    void duplicateTextIsNotWrittenTwice() {
        store.add("u-1", MemoryKind.SEMANTIC, "我喜欢用 Java", null, "conversation:c-1", 0.8, null);
        Optional<MemoryRecord> second = store.add("u-1", MemoryKind.SEMANTIC, "我喜欢用 Java",
                null, "conversation:c-2", 0.8, null);

        assertTrue(second.isEmpty(), "同一用户的同一条正文不重复写");
        assertEquals(1, store.list("u-1").size());
    }

    @Test
    void memoriesAreIsolatedPerUser() {
        store.add("u-1", MemoryKind.SEMANTIC, "我喜欢用 Java", null, null, 0.8, null);
        store.add("u-2", MemoryKind.SEMANTIC, "我喜欢用 Python", null, null, 0.8, null);

        assertEquals(1, store.list("u-1").size());
        assertEquals(1, store.list("u-2").size());
        assertEquals("我喜欢用 Java", store.list("u-1").get(0).text());
    }

    @Test
    void forgetDeletesOneMemory() {
        MemoryRecord record = store.add("u-1", MemoryKind.SEMANTIC, "我喜欢用 Java", null, null, 0.8, null)
                .orElseThrow();

        assertTrue(store.forget("u-1", record.id()));
        assertTrue(store.list("u-1").isEmpty(), "A8：记忆可删除");
        assertFalse(store.forget("u-1", record.id()), "重复删除返回 false");
        assertFalse(store.forget("u-2", record.id()), "不能删别的用户的记忆");
    }

    @Test
    void purgeExpiredRemovesOnlyExpired() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
        store.add("u-1", MemoryKind.SEMANTIC, "已过期的记忆", null, null, 0.8, past);
        store.add("u-1", MemoryKind.SEMANTIC, "还没过期的记忆", null, null, 0.8, future);
        store.add("u-1", MemoryKind.SEMANTIC, "永不过期的记忆", null, null, 0.8, null);

        int purged = store.purgeExpired(Instant.now());

        assertEquals(1, purged);
        List<String> texts = store.list("u-1").stream().map(MemoryRecord::text).toList();
        assertFalse(texts.contains("已过期的记忆"));
        assertTrue(texts.contains("还没过期的记忆"));
        assertTrue(texts.contains("永不过期的记忆"));
    }

    @Test
    void isExpiredFollowsExpiresAt() {
        Instant now = Instant.now();
        MemoryRecord active = new MemoryRecord("i-1", "u-1", MemoryKind.SEMANTIC, "x", null, null,
                null, 0.8, now, now.plus(1, ChronoUnit.DAYS));
        MemoryRecord expired = new MemoryRecord("i-2", "u-1", MemoryKind.SEMANTIC, "y", null, null,
                null, 0.8, now, now.minus(1, ChronoUnit.DAYS));
        MemoryRecord forever = new MemoryRecord("i-3", "u-1", MemoryKind.SEMANTIC, "z", null, null,
                null, 0.8, now, null);

        assertFalse(active.isExpired(now));
        assertTrue(expired.isExpired(now));
        assertFalse(forever.isExpired(now), "没有过期时间 = 永不过期");
    }

    @Test
    void embeddingRoundTripsWithDim() {
        float[] vector = new float[] {0.25f, -0.5f, 0.75f, 0.125f};
        MemoryRecord record = store.add("u-1", MemoryKind.SEMANTIC, "带向量的记忆",
                vector, null, 0.8, null).orElseThrow();

        assertEquals(4, record.dim());
        MemoryRecord reloaded = store.list("u-1").get(0);
        assertEquals(4, reloaded.dim());
        assertNotNull(reloaded.embedding());
        for (int i = 0; i < vector.length; i++) {
            assertEquals(vector[i], reloaded.embedding()[i], 1e-6);
        }
    }

    private MemoryProperties properties() {
        return new MemoryProperties(
                true,
                tempDir.resolve("memory.db").toString(),
                new MemoryProperties.Window(20),
                new MemoryProperties.Recall(5, 0.35, 30),
                new MemoryProperties.Embedding(true, "text-embedding-v3", 1024),
                new MemoryProperties.Extract(true, 0.8, 90));
    }
}
