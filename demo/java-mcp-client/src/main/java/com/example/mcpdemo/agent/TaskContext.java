package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.AgentState;
import com.example.mcpdemo.agent.model.TaskStatus;
import com.example.mcpdemo.agent.model.TraceStep;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次任务的上下文。
 *
 * <p>所有随请求变化的可变状态都放在这里，{@link TaskOrchestrator} 保持无状态，
 * 因此并发请求天然隔离。
 */
@Data
public class TaskContext {

    private final String taskId;
    private final String task;
    private final int maxSteps;
    private final Instant startedAt = Instant.now();

    /** 对话历史（含系统提示词之外的 user/assistant/tool 消息）。 */
    private final List<Message> messages = new ArrayList<>();
    /** trace 步骤。 */
    private final List<TraceStep> steps = new ArrayList<>();

    private AgentState state = AgentState.IDLE;
    private TaskStatus status = TaskStatus.OK;
    private String finalAnswer = "";
    /** 最近一次模型输出文本（可能为空，例如模型只输出 tool_calls）。 */
    private String lastAssistantText = "";
    /** 最近一轮的 ChatResponse（act 阶段需要）。 */
    private ChatResponse lastChatResponse;
    /** 空答案重试是否已用过（只重试一次）。 */
    private boolean answerRetried;
    private String errorCode;
    private String errorMessage;
    /** 降级（失败）步骤号；空表示没有降级步骤。 */
    private List<Integer> degradedSteps = new ArrayList<>();

    public TaskContext(String taskId, String task, int maxSteps) {
        this.taskId = taskId;
        this.task = task;
        this.maxSteps = maxSteps;
    }

}
