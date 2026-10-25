package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.TaskTrace;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2 编排器冒烟测试。
 *
 * <p>注意：@SpringBootTest 会启动完整应用（含 MCP 客户端初始化），
 * 运行前请先启动 8001/8002 两个 Python 服务，否则上下文启动会 fail-fast。
 */
@SpringBootTest
class TaskOrchestratorTest {

    /** 让 Spring 创建并注入编排器，这样构造器参数（含前缀生成器）才会被正确注入。 */
    @Resource
    private TaskOrchestrator orchestrator;

    @Test
    void run() {
        TaskContext tc = new TaskContext("001", "现在几点?", 10);
        TaskTrace trace = orchestrator.run(tc);
        //System.out.println(trace.finalAnswer());
        System.out.println(tc);
        assertNotNull(trace);
    }

    @Test
    void run2() {
        TaskContext tc = new TaskContext("002", "10 - 1 + 2 等于多少? 必须先使用 subtract 再使用 add 工具", 10);
        TaskTrace trace = orchestrator.run(tc);
        System.out.println(tc);
        assertNotNull(trace);
    }
}
