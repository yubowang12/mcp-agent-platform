package com.example.mcpdemo.memory.longterm;

import com.example.mcpdemo.memory.config.MemoryProperties;
import com.example.mcpdemo.memory.infra.SqliteMemoryDb;
import com.example.mcpdemo.memory.model.MemoryKind;
import com.example.mcpdemo.memory.model.RecalledMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量召回（A4 的召回侧、A6 的过期过滤）。
 *
 * <p>用一个按关键词返回固定向量的假 {@link EmbeddingModel}，不联网、不调 DashScope。
 */
class MemoryRecallServiceTest {

    @TempDir
    Path tempDir;

    /** 每个用例独立的临时 SQLite 库（写在 JUnit 的 @TempDir 里）。 */
    private SqliteMemoryDb db;
    /** 被测存储：直接插带向量的记忆，构造召回候选。 */
    private SqliteMemoryStore store;
    /** 被测召回服务。 */
    private MemoryRecallService recallService;

    @BeforeEach
    void setUp() {
        db = new SqliteMemoryDb(props(0.35, 5));
        store = new SqliteMemoryStore(db);
        recallService = new MemoryRecallService(store, embeddingService(props(0.35, 5)), props(0.35, 5));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void recallsMostSimilarMemoryFirst() {
        store.add("u-1", MemoryKind.SEMANTIC, "我喜欢 Java 后端", new float[] {1f, 0f, 0f},
                "conversation:c-1", 0.9, null);
        store.add("u-1", MemoryKind.SEMANTIC, "我偶尔写 Python 脚本", new float[] {0.6f, 0.8f, 0f},
                "conversation:c-1", 0.9, null);

        List<RecalledMemory> recalled = recallService.recall("u-1", "Java 相关的问题");

        assertFalse(recalled.isEmpty());
        assertEquals("我喜欢 Java 后端", recalled.get(0).memory().text());
        assertTrue(recalled.get(0).cosine() > 0.99, "同向向量余弦应为 1");
        assertTrue(recalled.get(0).score() > 0.9, "刚写入的记忆衰减接近 1");
    }

    @Test
    void unrelatedMemoryIsFilteredByMinScore() {
        store.add("u-1", MemoryKind.SEMANTIC, "我喜欢 Python", new float[] {0f, 1f, 0f},
                null, 0.8, null);

        List<RecalledMemory> recalled = recallService.recall("u-1", "Java 相关的问题");

        assertTrue(recalled.isEmpty(), "余弦 0 低于 min-score，不应注入");
    }

    @Test
    void expiredMemoryIsNeverRecalled() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        store.add("u-1", MemoryKind.SEMANTIC, "过期的偏好", new float[] {1f, 0f, 0f},
                null, 0.9, past);

        List<RecalledMemory> recalled = recallService.recall("u-1", "Java 相关的问题");

        assertTrue(recalled.isEmpty(), "A6：过期记忆不再召回");
    }

    @Test
    void distinctUsersAreIsolated() {
        store.add("u-2", MemoryKind.SEMANTIC, "别人的偏好", new float[] {1f, 0f, 0f}, null, 0.9, null);

        assertTrue(recallService.recall("u-1", "Java 相关的问题").isEmpty(), "召回按 userId 隔离");
    }

    @Test
    void topKTruncatesResult() {
        for (int i = 0; i < 8; i++) {
            store.add("u-1", MemoryKind.SEMANTIC, "偏好 " + i, new float[] {1f, 0f, 0f}, null, 0.8, null);
        }

        List<RecalledMemory> recalled = recallService.recall("u-1", "Java 相关的问题");

        assertEquals(5, recalled.size(), "top-k 默认 5，控制上下文膨胀");
    }

    @Test
    void dimensionMismatchIsSkipped() {
        store.add("u-1", MemoryKind.SEMANTIC, "三维记忆", new float[] {1f, 0f, 0f}, null, 0.9, null);
        store.add("u-1", MemoryKind.SEMANTIC, "二维记忆", new float[] {1f, 0f}, null, 0.9, null);

        List<RecalledMemory> recalled = recallService.recall("u-1", "Java 相关的问题");

        assertEquals(1, recalled.size(), "维度与 query 不一致的记忆跳过（换了 embedding 模型的情况）");
        assertEquals("三维记忆", recalled.get(0).memory().text());
    }

    @Test
    void memoryWithoutVectorIsSkipped() {
        store.add("u-1", MemoryKind.SEMANTIC, "没有向量的记忆", null, null, 0.9, null);

        assertTrue(recallService.recall("u-1", "Java 相关的问题").isEmpty());
    }

    @Test
    void recallIsEmptyWithoutEmbeddingModel() {
        MemoryProperties props = props(0.35, 5);
        EmbeddingService broken = new EmbeddingService(new FailingEmbeddingModel(), props);
        MemoryRecallService service = new MemoryRecallService(store, broken, props);
        store.add("u-1", MemoryKind.SEMANTIC, "我喜欢 Java", new float[] {1f, 0f, 0f}, null, 0.9, null);

        assertTrue(service.recall("u-1", "Java 相关的问题").isEmpty(), "embedding 失败时按无召回降级");
    }

    @Test
    void timeDecayHalvesAtHalfLife() {
        Instant now = Instant.now();
        assertEquals(0.5, MemoryRecallService.decay(now.minus(30, ChronoUnit.DAYS), now, 30), 1e-6);
        assertEquals(0.25, MemoryRecallService.decay(now.minus(60, ChronoUnit.DAYS), now, 30), 1e-6);
        assertEquals(1.0, MemoryRecallService.decay(now, now, 30), 1e-6);
        assertEquals(1.0, MemoryRecallService.decay(now.minus(100, ChronoUnit.DAYS), now, 0),
                "半衰期配 0 表示不衰减");
    }

    private EmbeddingService embeddingService(MemoryProperties props) {
        return new EmbeddingService(new KeywordEmbeddingModel(), props);
    }

    private MemoryProperties props(double minScore, int topK) {
        return new MemoryProperties(
                true,
                tempDir.resolve("memory.db").toString(),
                new MemoryProperties.Window(20),
                new MemoryProperties.Recall(topK, minScore, 30),
                new MemoryProperties.Embedding(true, "text-embedding-v3", 3),
                new MemoryProperties.Extract(true, 0.8, 90));
    }

    /** 关键词 → 固定三维向量（Java / Python / 其他）。 */
    private static final class KeywordEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            String text = request.getInstructions().get(0);
            float[] vector = text.contains("Java")
                    ? new float[] {1f, 0f, 0f}
                    : text.contains("Python") ? new float[] {0f, 1f, 0f} : new float[] {0f, 0f, 1f};
            return new EmbeddingResponse(List.of(new Embedding(vector, 0)));
        }

        @Override
        public float[] embed(Document document) {
            return new float[0];
        }
    }

    private static final class FailingEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new IllegalStateException("模拟 DashScope 不可用");
        }

        @Override
        public float[] embed(Document document) {
            return new float[0];
        }
    }
}
