package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.TaskStatus;
import com.example.mcpdemo.agent.model.TaskTrace;
import com.example.mcpdemo.agent.trace.TraceRecorder;
import com.example.mcpdemo.config.PlannerProperties;
import com.example.mcpdemo.resilience.CircuitBreakerProperties;
import com.example.mcpdemo.resilience.CircuitBreakerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 无工具可用时的行为测试：不依赖 Spring / Ollama / MCP，纯单元测试。
 *
 * <p>场景：没有任何 MCP 客户端（工具目录为空），模型直接给出文本答复，
 * 期望任务正常结束（0 步、状态 OK、有最终回答），不抛异常。
 */
class NoToolsAvailableTest {

    @Test
    void noToolsAvailable() {
        // 模拟 ChatClient 调用链：prompt(...).system(...).call().chatResponse()
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        AssistantMessage message = new AssistantMessage("你好，我是助手。");
        ChatResponse response = new ChatResponse(List.of(new Generation(message)));

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(response);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        // 无 MCP 客户端；trace 直接写到系统临时目录（目录存在，append 不会报 warn）
        String traceDir = System.getProperty("java.io.tmpdir");
        TaskOrchestrator orchestrator = new TaskOrchestrator(
                builder,
                List.of(),
                (connection, tool) -> "noop_" + tool.name(),
                new TraceRecorder(new ObjectMapper(), traceDir),
                new PlannerProperties("qwen2.5:3b", 120, 60, 45, 8, 32, 16, 64, 8),
                new CircuitBreakerRegistry(new CircuitBreakerProperties(3, 30, 1)));

        TaskTrace trace = orchestrator.run(new TaskContext("no-tools", "你好", 3));
        System.out.println(trace);

        assertEquals(0, trace.usedSteps());
        assertEquals(TaskStatus.OK, trace.status());
        assertNotNull(trace.finalAnswer());
        assertTrue(trace.finalAnswer().contains("助手"));
    }
}
