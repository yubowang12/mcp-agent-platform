package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.TaskStatus;
import com.example.mcpdemo.agent.model.TaskTrace;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2/M3 编排器冒烟测试（集成）。
 *
 * <p>注意：@SpringBootTest 会启动完整应用（含 MCP 客户端初始化），
 * 运行前请先启动 8001/8002 两个 Python 服务，否则上下文启动会 fail-fast。
 */
@SpringBootTest
class TaskOrchestratorTest {

    /** 让 Spring 创建并注入编排器，构造器参数（含前缀生成器、配置）才会被正确注入。 */
    @Resource
    private TaskOrchestrator orchestrator;

    @Test
    void run() {
        TaskContext tc = new TaskContext("001", "现在几点?", 10);
        TaskTrace trace = orchestrator.run(tc);
        System.out.println(trace);

        assertNotNull(trace);
        assertEquals(TaskStatus.OK, trace.status());
        assertTrue(trace.usedSteps() >= 1, "时间类任务应至少调用一次工具");
        assertNotNull(trace.finalAnswer());
        assertFalse(trace.finalAnswer().isBlank());
    }

    @Test
    void run2() {
        TaskContext tc = new TaskContext("002", "10 - 1 + 2 等于多少? 必须先使用 subtract 再使用 add 工具", 10);
        TaskTrace trace = orchestrator.run(tc);
        System.out.println(trace);

        assertNotNull(trace);
        assertEquals(TaskStatus.OK, trace.status());
        assertTrue(trace.usedSteps() >= 2, "两步任务应至少调用两次工具");
        assertNotNull(trace.finalAnswer());
    }
}
