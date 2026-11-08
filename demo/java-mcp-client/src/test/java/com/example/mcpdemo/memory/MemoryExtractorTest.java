package com.example.mcpdemo.memory;

import com.example.mcpdemo.memory.config.MemoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 / A5：规则抽取写什么、不写什么。
 *
 * <p>A3：用户明确陈述偏好 → 抽出一条 semantic（带置信度）。
 * <p>A5：闲聊、天气、时间这类临时信息 → 一条都不写。
 */
class MemoryExtractorTest {

    /** 被测抽取器（规则固定，直接用默认配置构造）。 */
    private final MemoryExtractor extractor = new MemoryExtractor(properties());

    @Test
    void explicitPreferenceIsExtracted() {
        List<MemoryCandidate> candidates = extractor.extract(session(
                "我喜欢用 Java 写后端，请记住这个偏好。", "好的，我记住了。", 0));

        assertEquals(1, candidates.stream().filter(c -> c.kind() == MemoryKind.SEMANTIC).count(),
                "A3：明确陈述偏好应抽出一条 semantic");
        MemoryCandidate semantic = semanticOf(candidates);
        assertTrue(semantic.confidence() > 0.8, "明确要求记住的置信度应高于默认值");
        assertTrue(semantic.text().contains("Java"));
        assertNull(semantic.ttlDays(), "semantic 默认不过期");
    }

    @Test
    void explicitRememberIsExtractedEvenWhenOneOff() {
        List<MemoryCandidate> candidates = extractor.extract(new SessionSummary(
                "s-1", "u-1", "记住：我叫小明，2027 届应届生。", "记住了。",
                List.of(), 0, true, true));

        assertTrue(candidates.stream().anyMatch(c -> c.kind() == MemoryKind.SEMANTIC),
                "用户明确说\"记住\"时，一次性会话也要写 semantic");
        assertTrue(candidates.stream().noneMatch(c -> c.kind() == MemoryKind.EPISODIC),
                "一次性会话不写会话摘要");
    }

    @Test
    void weatherIsNotRemembered() {
        assertTrue(extractor.extract(session("北京今天天气怎么样？", "晴，25 度。", 1)).isEmpty(),
                "A5：天气不写记忆");
    }

    @Test
    void timeIsNotRemembered() {
        assertTrue(extractor.extract(session("现在几点了？", "现在是 10:30。", 1)).isEmpty(),
                "A5：时间不写记忆");
    }

    @Test
    void greetingIsNotRemembered() {
        assertTrue(extractor.extract(session("你好", "你好，有什么可以帮你？", 0)).isEmpty(),
                "A5：寒暄不写记忆");
    }

    @Test
    void arithmeticIsNotRemembered() {
        assertTrue(extractor.extract(session("10 - 1 + 2 等于多少？", "11", 2)).isEmpty(),
                "A5：一次性算术不写记忆");
    }

    @Test
    void questionIsNotMistakenForFact() {
        List<MemoryCandidate> candidates = extractor.extract(
                session("我叫什么名字？我正在准备什么岗位的面试？", "你叫小明。", 0));

        assertTrue(candidates.stream().noneMatch(c -> c.kind() == MemoryKind.SEMANTIC),
                "问句不是陈述，不能记成事实");
    }

    @Test
    void substantiveSessionWritesSummary() {
        List<MemoryCandidate> candidates = extractor.extract(session(
                "帮我查一下现在时间，然后告诉我要不要现在去面试。", "现在 10:30，建议提前出发。", 1));

        // 任务里含"时间"→ 属于一次性信息，语义记忆不写；摘要也不写（避免把时间问答留存）
        assertTrue(candidates.isEmpty(), "含时间查询的会话不写长期记忆");

        List<MemoryCandidate> normal = extractor.extract(session(
                "帮我把这段自我介绍改得更适合 Agent 开发实习岗位", "改好了，突出项目经历。", 1));
        MemoryCandidate episodic = normal.stream()
                .filter(c -> c.kind() == MemoryKind.EPISODIC)
                .findFirst()
                .orElseThrow(() -> new AssertionError("实质性会话应写一条会话摘要"));
        assertTrue(episodic.text().startsWith("会话摘要"));
        assertTrue(episodic.text().contains("工具："));
        assertEquals(90, episodic.ttlDays(), "episodic 按配置的保留天数过期");
    }

    @Test
    void failedSessionWritesNothing() {
        assertTrue(extractor.extract(new SessionSummary(
                "c-1", "u-1", "帮我把这段自我介绍改得更适合 Agent 开发实习岗位", "",
                List.of("demo_get_time"), 1, false, false)).isEmpty(), "失败会话不写记忆");
    }

    @Test
    void disabledExtractionWritesNothing() {
        MemoryProperties disabled = new MemoryProperties(
                true,
                "memory.db",
                new MemoryProperties.Window(20),
                new MemoryProperties.Recall(5, 0.35, 30),
                new MemoryProperties.Embedding(true, "text-embedding-v3", 1024),
                new MemoryProperties.Extract(false, 0.8, 90));

        assertTrue(new MemoryExtractor(disabled)
                .extract(session("我喜欢用 Java，请记住。", "好。", 0)).isEmpty());
    }

    private MemoryCandidate semanticOf(List<MemoryCandidate> candidates) {
        return candidates.stream()
                .filter(c -> c.kind() == MemoryKind.SEMANTIC)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 semantic 记忆"));
    }

    private SessionSummary session(String task, String answer, int steps) {
        return new SessionSummary("c-1", "u-1", task, answer, List.of("demo_get_time"), steps, true, false);
    }

    private MemoryProperties properties() {
        return new MemoryProperties(
                true,
                "memory.db",
                new MemoryProperties.Window(20),
                new MemoryProperties.Recall(5, 0.35, 30),
                new MemoryProperties.Embedding(true, "text-embedding-v3", 1024),
                new MemoryProperties.Extract(true, 0.8, 90));
    }
}
