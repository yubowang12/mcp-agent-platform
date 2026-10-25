package com.example.mcpdemo.web;

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

    private final TaskOrchestrator orchestrator;

    public TaskController(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/task")
    public TaskTrace run(@RequestBody TaskRequest request) {
        String task = request.task();
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task 不能为空");
        }
        int maxSteps = request.maxSteps() == null ? 10 : Math.min(Math.max(request.maxSteps(), 1), 20);
        String taskId = "t-" + UUID.randomUUID().toString().substring(0, 8);
        TaskContext ctx = new TaskContext(taskId, task, maxSteps);
        return orchestrator.run(ctx);
    }

    public record TaskRequest(String task, Integer maxSteps) {
    }
}
