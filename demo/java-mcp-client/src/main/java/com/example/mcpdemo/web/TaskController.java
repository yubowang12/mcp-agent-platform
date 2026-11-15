package com.example.mcpdemo.web;

import com.example.mcpdemo.agent.TaskConcurrencyLimiter;
import com.example.mcpdemo.agent.TaskContext;
import com.example.mcpdemo.agent.TaskOrchestrator;
import com.example.mcpdemo.agent.model.TaskTrace;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * M2：任务入口。
 *
 * <p>Controller 负责 Web 边界（参数校验、生成 taskId）并创建本次请求的
 * {@link TaskContext}；编排逻辑由无状态的 {@link TaskOrchestrator} 完成。
 */
@RestController
@RequestMapping("/api")
public class TaskController {

    /** 编排器（无状态，可单例复用；/api/task 的全部编排逻辑都在它里面）。 */
    private final TaskOrchestrator orchestrator;
    /** W2：入口并发闸门，满了直接 503，不把请求堆进 LLM 队列。 */
    private final TaskConcurrencyLimiter limiter;

    public TaskController(TaskOrchestrator orchestrator, TaskConcurrencyLimiter limiter) {
        this.orchestrator = orchestrator;
        this.limiter = limiter;
    }

    @PostMapping("/task")
    public TaskTrace run(@RequestBody TaskRequest request) {
        String task = request.task();
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task 不能为空");
        }
        if (!limiter.tryEnter()) {
            throw new TaskBusyException(limiter.inFlight(), limiter.capacity());
        }
        try {
            int maxSteps = request.maxSteps() == null ? 10 : Math.min(Math.max(request.maxSteps(), 1), 20);
            String taskId = "t-" + UUID.randomUUID().toString().substring(0, 8);
            // 记忆层 v1（规格第 7 节，已定 A）：sessionId 可选，缺省生成一次性会话，向后兼容现有脚本与评测
            boolean oneOff = request.sessionId() == null || request.sessionId().isBlank();
            String conversationId = oneOff ? "s-" + UUID.randomUUID() : request.sessionId().trim();
            String userId = (request.userId() == null || request.userId().isBlank())
                    ? "default"
                    : request.userId().trim();
            TaskContext ctx = new TaskContext(taskId, task, maxSteps, conversationId, userId, oneOff);
            return orchestrator.run(ctx);
        } finally {
            limiter.exit();
        }
    }

    /**
     * /api/task 请求体。
     *
     * <p>{@code sessionId} / {@code userId} 都是可选的：老调用方（脚本、M6 评测）不传也能跑，
     * 传了才启用短期历史与跨会话长期记忆。
     */
    public record TaskRequest(String task, Integer maxSteps, String sessionId, String userId) {
    }
}
