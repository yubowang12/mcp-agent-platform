package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.AgentState;
import com.example.mcpdemo.agent.model.TaskStatus;
import com.example.mcpdemo.agent.model.TraceStep;
import com.example.mcpdemo.memory.model.MemoryRecord;
import com.example.mcpdemo.memory.model.RecalledMemory;
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

    /** 任务 id（{@code t-<8位uuid>}，每次请求生成，贯穿日志与 trace）。 */
    private final String taskId;
    /** 用户原始任务文本。 */
    private final String task;
    /** 请求允许的最大步数（工具调用次数上限）。 */
    private final int maxSteps;
    /** 请求开始时间（用于总超时判断与 trace 耗时）。 */
    private final Instant startedAt = Instant.now();

    /** 会话 id：记忆层的 conversationId（请求体 sessionId；为空表示不落短期记忆）。 */
    private final String conversationId;
    /** 用户 id：长期记忆按它隔离（请求体 userId，缺省 default）。 */
    private final String userId;
    /** 是否一次性会话（请求没带 sessionId）：一次性会话不写会话摘要。 */
    private final boolean oneOffSession;

    /** 对话历史（含系统提示词之外的 user/assistant/tool 消息）。 */
    private final List<Message> messages = new ArrayList<>();
    /** trace 步骤。 */
    private final List<TraceStep> steps = new ArrayList<>();
    /** 本次会话新增的长期记忆（trace 用）。 */
    private final List<MemoryRecord> writtenMemories = new ArrayList<>();
    /** 本次会话召回的长期记忆（trace 用）。 */
    private final List<RecalledMemory> recalledMemories = new ArrayList<>();
    /** 注入 system 段的记忆字符数（trace 用，度量上下文膨胀）。 */
    private int memoryInjectedChars;

    /** 编排状态机（IDLE / RUNNING / FINISHED / ERROR），控制 ReAct 循环是否继续。 */
    private AgentState state = AgentState.IDLE;
    /** 任务终态，最终写进 trace.status（默认 OK，失败路径会改）。 */
    private TaskStatus status = TaskStatus.OK;
    /** 最终答复（成功是模型文本，失败是兜底文案）。 */
    private String finalAnswer = "";
    /** 最近一次模型输出文本（可能为空，例如模型只输出 tool_calls）。 */
    private String lastAssistantText = "";
    /** 最近一轮的 ChatResponse（act 阶段需要）。 */
    private ChatResponse lastChatResponse;
    /** 空答案重试是否已用过（只重试一次）。 */
    private boolean answerRetried;
    /** 失败原因码（如 {@code TOOL_TIMEOUT} / {@code EMPTY_ANSWER}）；成功为 null。 */
    private String errorCode;
    /** 失败原因的可读说明；成功为 null。 */
    private String errorMessage;
    /** 降级（失败）步骤号；空表示没有降级步骤。 */
    private List<Integer> degradedSteps = new ArrayList<>();

    public TaskContext(String taskId, String task, int maxSteps) {
        this(taskId, task, maxSteps, null, "default", true);
    }

    public TaskContext(String taskId, String task, int maxSteps, String conversationId, String userId) {
        this(taskId, task, maxSteps, conversationId, userId, false);
    }

    public TaskContext(String taskId, String task, int maxSteps, String conversationId, String userId,
                       boolean oneOffSession) {
        this.taskId = taskId;
        this.task = task;
        this.maxSteps = maxSteps;
        this.conversationId = conversationId;
        this.userId = (userId == null || userId.isBlank()) ? "default" : userId;
        this.oneOffSession = oneOffSession;
    }

}
