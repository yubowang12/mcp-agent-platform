package com.example.mcpdemo.web;

import com.example.mcpdemo.agent.TaskConcurrencyLimiter;
import com.example.mcpdemo.agent.TaskOrchestrator;
import com.example.mcpdemo.agent.model.TaskTrace;
import com.example.mcpdemo.config.PlannerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2：入口闸门的 HTTP 契约（不启动 Spring 上下文，standalone MockMvc）。
 */
class TaskControllerConcurrencyTest {

    private static PlannerProperties props(int maxConcurrent) {
        return new PlannerProperties("qwen-plus", 300, 60, 45, 8, 32, 16, 64, maxConcurrent);
    }

    @Test
    void returns503WithRetryAfterWhenGateIsFull() throws Exception {
        TaskConcurrencyLimiter limiter = new TaskConcurrencyLimiter(props(1));
        assertTrue(limiter.tryEnter(), "先占满唯一的执行位");
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new TaskController(orchestrator, limiter))
                .build();

        mvc.perform(post("/api/task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));

        verifyNoInteractions(orchestrator);
    }

    @Test
    void releasesPermitAfterEachRequest() throws Exception {
        TaskConcurrencyLimiter limiter = new TaskConcurrencyLimiter(props(1));
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        when(orchestrator.run(any())).thenReturn(mock(TaskTrace.class));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new TaskController(orchestrator, limiter))
                .build();

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/task")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"task\":\"你好\"}"))
                    .andExpect(status().isOk());
        }
        assertEquals(0, limiter.inFlight(), "每个请求结束都要归还许可，否则第二次就会 503");
    }
}
