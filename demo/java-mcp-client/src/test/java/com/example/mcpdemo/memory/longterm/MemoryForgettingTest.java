package com.example.mcpdemo.memory.longterm;

import com.example.mcpdemo.memory.config.MemoryProperties;
import com.example.mcpdemo.memory.infra.SqliteMemoryDb;
import com.example.mcpdemo.memory.model.MemoryCandidate;
import com.example.mcpdemo.memory.model.MemoryKind;
import com.example.mcpdemo.memory.model.RecalledMemory;
import com.example.mcpdemo.memory.model.SessionSummary;
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
 * A6 / P0.6：过期之后必须"真的忘掉"。
 *
 * <p>复核时发现的问题：同一事实（邮箱）既写进 semantic 原句，又被 episodic 摘要抄了一遍，
 * 只过期 semantic 时，摘要仍会把邮箱带出来。方案 b 让摘要只记"做了什么 + 结论"，
 * 这个用例把复核脚本的三步固化成回归测试。
 */
class MemoryForgettingTest {

    @TempDir
    Path tempDir;

    private SqliteMemoryDb db;
    private SqliteMemoryStore store;
    private MemoryExtractor extractor;
    private MemoryRecallService recallService;

    @BeforeEach
    void setUp() {
        MemoryProperties props = props();
        db = new SqliteMemoryDb(props);
        store = new SqliteMemoryStore(db);
        extractor = new MemoryExtractor(props);
        recallService = new MemoryRecallService(store,
                new EmbeddingService(new ConstantEmbeddingModel(), props), props);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void expiredSemanticLeavesNoTraceInEpisodicSummary() {
        String email = "m6-review@example.com";

        // 第 1 步：用户明确要求记住邮箱 → 写入 semantic（原句）+ episodic（摘要）
        SessionSummary session = new SessionSummary("c-1", "u-1",
                "记住：我的邮箱是 " + email,
                "已记录你的邮箱：" + email,
                List.of("terminate"), 1, true, false);
        List<MemoryCandidate> candidates = extractor.extract(session);
        MemoryCandidate semantic = candidates.stream()
                .filter(c -> c.kind() == MemoryKind.SEMANTIC).findFirst().orElseThrow();
        MemoryCandidate episodic = candidates.stream()
                .filter(c -> c.kind() == MemoryKind.EPISODIC).findFirst().orElseThrow();

        // 第 2 步：把 semantic 弄成已过期，episodic 仍在有效期内
        float[] vector = new float[] {1f, 0f, 0f};
        store.add("u-1", MemoryKind.SEMANTIC, semantic.text(), vector, "conversation:c-1",
                semantic.confidence(), Instant.now().minus(1, ChronoUnit.DAYS));
        store.add("u-1", MemoryKind.EPISODIC, episodic.text(), vector, "conversation:c-1",
                episodic.confidence(), Instant.now().plus(90, ChronoUnit.DAYS));

        // 第 3 步：问邮箱
        List<RecalledMemory> recalled = recallService.recall("u-1", "我的邮箱是什么？");

        assertFalse(recalled.isEmpty(), "episodic 本身没过期，仍应被召回（否则这条用例是空跑）");
        assertTrue(recalled.stream().allMatch(r -> r.memory().kind() == MemoryKind.EPISODIC),
                "过期的 semantic 不应出现在召回结果里");
        assertTrue(recalled.stream().noneMatch(r -> r.memory().text().contains(email)),
                "A6：过期后任何被召回的记忆都不得带出邮箱");
        assertEquals(1, recalled.size(), "只剩那条会话摘要");
    }

    private MemoryProperties props() {
        return new MemoryProperties(
                true,
                tempDir.resolve("memory.db").toString(),
                new MemoryProperties.Window(20),
                new MemoryProperties.Recall(5, 0.35, 30),
                new MemoryProperties.Embedding(true, "text-embedding-v3", 3),
                new MemoryProperties.Extract(true, 0.8, 90));
    }

    /** 所有文本都返回同一个向量：让"召回"只受过期与分数门槛影响，测试不依赖真实 embedding。 */
    private static final class ConstantEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(List.of(new Embedding(new float[] {1f, 0f, 0f}, 0)));
        }

        @Override
        public float[] embed(Document document) {
            return new float[0];
        }
    }
}
