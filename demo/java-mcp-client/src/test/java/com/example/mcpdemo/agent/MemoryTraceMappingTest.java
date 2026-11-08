package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.MemoryTrace;
import com.example.mcpdemo.agent.model.TaskStatus;
import com.example.mcpdemo.agent.model.TaskTrace;
import com.example.mcpdemo.memory.MemoryKind;
import com.example.mcpdemo.memory.MemoryRecord;
import com.example.mcpdemo.memory.RecalledMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * trace 的 memory 字段（A4：trace 里能看到 recalled / written）。
 *
 * <p>纯单测：不启动 Spring、不需要 MCP 服务或模型。
 */
class MemoryTraceMappingTest {

    @Test
    void mapsRecalledAndWritten() {
        TaskContext ctx = new TaskContext("t-1", "我接下来该准备什么？", 10, "u-1-s-2", "u-1", false);
        ctx.setMemoryInjectedChars(338);
        ctx.getRecalledMemories().add(new RecalledMemory(
                record("我喜欢 Java 后端", MemoryKind.SEMANTIC, 0.9), 0.474, 0.5, 0.95));
        ctx.getWrittenMemories().add(record("我叫小明", MemoryKind.SEMANTIC, 0.95));

        MemoryTrace trace = TaskOrchestrator.memoryTraceOf(ctx);

        assertEquals("u-1-s-2", trace.conversationId());
        assertEquals("u-1", trace.userId());
        assertEquals(338, trace.injectedChars());
        assertEquals(1, trace.recalled().size());
        assertEquals("我喜欢 Java 后端", trace.recalled().get(0).text());
        assertEquals(0.474, trace.recalled().get(0).score(), 1e-9);
        assertEquals(0.5, trace.recalled().get(0).cosine(), 1e-9);
        assertEquals("semantic", trace.recalled().get(0).kind());
        assertEquals(1, trace.written().size());
        assertEquals("semantic", trace.written().get(0).kind());
        assertEquals(0.95, trace.written().get(0).confidence(), 1e-9);
        assertEquals("conversation:c-1", trace.written().get(0).source());
    }

    @Test
    void emptyWhenNoMemoryActivity() {
        TaskContext ctx = new TaskContext("t-2", "现在几点", 3);

        MemoryTrace trace = TaskOrchestrator.memoryTraceOf(ctx);

        assertNotNull(trace);
        assertTrue(trace.recalled().isEmpty());
        assertTrue(trace.written().isEmpty());
        assertEquals(0, trace.injectedChars());
    }

    @Test
    void longMemoryTextIsAbbreviatedInTrace() {
        TaskContext ctx = new TaskContext("t-3", "任务", 3, "c-1", "u-1", false);
        ctx.getRecalledMemories().add(new RecalledMemory(
                record("很长的记忆".repeat(100), MemoryKind.EPISODIC, 0.6), 0.5, 0.5, 1.0));

        MemoryTrace trace = TaskOrchestrator.memoryTraceOf(ctx);

        assertTrue(trace.recalled().get(0).text().length() <= 121, "trace 里正文要截断，别把 trace 撑爆");
    }

    @Test
    void traceJsonContainsMemorySection() throws Exception {
        TaskContext ctx = new TaskContext("t-4", "任务", 3, "c-1", "u-1", false);
        ctx.getRecalledMemories().add(new RecalledMemory(
                record("我喜欢 Java", MemoryKind.SEMANTIC, 0.9), 0.8, 0.8, 1.0));
        TaskTrace trace = new TaskTrace("t-4", "任务", TaskStatus.OK, "qwen-plus", 3, 0,
                Instant.now().toString(), Instant.now().toString(), 10L, "答案",
                List.of(), false, null, null, List.of(), null, null,
                TaskOrchestrator.memoryTraceOf(ctx));

        String json = new ObjectMapper().writeValueAsString(trace);

        assertTrue(json.contains("\"memory\""), "trace JSON 必须有 memory 字段（A4）");
        assertTrue(json.contains("\"recalled\""));
        assertTrue(json.contains("我喜欢 Java"));
    }

    private MemoryRecord record(String text, MemoryKind kind, double confidence) {
        return new MemoryRecord("id-" + text.hashCode(), "u-1", kind, text, null, null,
                "conversation:c-1", confidence, Instant.now(), null);
    }
}
