package com.example.mcpdemo.agent;

import com.example.mcpdemo.agent.model.AgentState;
import com.example.mcpdemo.agent.model.TaskStatus;
import com.example.mcpdemo.agent.model.TaskTrace;
import com.example.mcpdemo.agent.model.TraceStep;
import com.example.mcpdemo.agent.trace.TraceRecorder;
import com.example.mcpdemo.config.McpToolPrefixConfig;
import com.example.mcpdemo.config.PlannerProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * M2 规划型编排器（无状态）。
 *
 * <p>手动 ReAct 循环：think（模型决策）→ act（执行 tool_calls）→ 回填 → 直到
 * 模型不再调工具、调用 terminate、达到 maxSteps 或任务超时。
 *
 * <p>所有请求级状态都在 {@link TaskContext}，本类可安全单例复用。
 */
@Component
@Slf4j
public class TaskOrchestrator {

    /** LLM 调用线程池：用于实现单次 LLM 调用的超时保护。 */
    private static final ExecutorService LLM_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-call");
        t.setDaemon(true);
        return t;
    });

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是 Agent 集群的调度器。任务：
            1) 把用户任务拆成最小可执行步骤；
            2) 每步只选一个工具，参数必须符合该工具的 schema；
            3) 信息足够时立即停止调用，直接给出最终答复；
            4) 工具描述里写明了能力边界，越界的需求不要硬套工具，直接说明原因；
            5) 任务完成或无法继续推进时，调用 terminate 工具结束任务。

            规则：
            - 每步只能调一个工具，不要并行猜测多个；
            - 调用前先确认参数齐全；缺参数则向用户追问；
            - 最多 %d 步，超出则基于已有信息作答。
            """;

    private final ChatClient chatClient;
    private final ToolCallback[] tools;
    private final ToolCallingManager toolCallingManager;
    private final TraceRecorder traceRecorder;
    private final PlannerProperties props;
    /** 前缀名（sanitize 后） -> 原始 server 名，用于 trace.serverKey。 */
    private final Map<String, String> serverNamesByPrefix;

    public TaskOrchestrator(ChatClient.Builder chatClientBuilder,
                            List<McpSyncClient> mcpClients,
                            McpToolNamePrefixGenerator mcpToolNamePrefixGenerator,
                            TraceRecorder traceRecorder,
                            PlannerProperties props) {
        this.chatClient = chatClientBuilder.build();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.traceRecorder = traceRecorder;
        this.props = props;

        // MCP 工具：前缀化 + 单步超时包装（超时秒数来自配置）
        ToolCallback[] mcpTools = new SyncMcpToolCallbackProvider(
                (connection, tool) -> true,
                mcpToolNamePrefixGenerator,
                mcpClients.toArray(McpSyncClient[]::new)).getToolCallbacks();

        List<ToolCallback> allTools = new ArrayList<>();
        for (ToolCallback callback : mcpTools) {
            allTools.add(new TimeoutToolCallback(callback, props.toolTimeoutSeconds()));
        }
        // 本地终止工具（与 MCP 无关，不加前缀、不包装超时）
        allTools.addAll(List.of(MethodToolCallbackProvider.builder()
                .toolObjects(new TerminateTool())
                .build()
                .getToolCallbacks()));
        this.tools = allTools.toArray(ToolCallback[]::new);

        Map<String, String> serverNames = new LinkedHashMap<>();
        for (McpSyncClient client : mcpClients) {
            McpSchema.Implementation info = client.getServerInfo();
            if (info != null && info.name() != null) {
                serverNames.put(McpToolPrefixConfig.sanitize(info.name()), info.name());
            }
        }
        this.serverNamesByPrefix = Map.copyOf(serverNames);
    }

    /**
     * 执行一次任务。
     */
    public TaskTrace run(TaskContext ctx) {
        ctx.setState(AgentState.RUNNING);
        ctx.getMessages().add(new UserMessage(ctx.getTask()));

        // 工具回调同时挂在 ChatOptions 上：模型调用用它，executeToolCalls 也用它按名字解析。
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(props.model())
                .toolCallbacks(List.of(tools))
                .internalToolExecutionEnabled(false)
                .build();

        Instant deadline = ctx.getStartedAt().plus(Duration.ofSeconds(props.taskTimeoutSeconds()));
        try {
            while (ctx.getState() == AgentState.RUNNING
                    && ctx.getSteps().size() < ctx.getMaxSteps()
                    && Instant.now().isBefore(deadline)) {

                // 每轮开始清空当前线程的耗时样本，防止上一轮/上一请求残留
                TimeoutToolCallback.clear();

                long llmStart = System.nanoTime();
                Prompt prompt = new Prompt(ctx.getMessages(), options);
                ChatResponse response = callLlm(prompt, ctx.getMaxSteps());
                log.info("{}的思考: {}", props.model(), response.getResult().getOutput().getText());
                log.info("{}选择了 {} 个工具调用", props.model(),
                        response.getResult().getOutput().getToolCalls().size());
                long llmLatencyMs = (System.nanoTime() - llmStart) / 1_000_000;

                AssistantMessage assistant = response.getResult().getOutput();
                ctx.setLastChatResponse(response);
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    ctx.setLastAssistantText(text);
                }

                // 不再调用工具 -> 结束（空答案先重试一次，仍空则降级，不标 OK）
                if (assistant.getToolCalls().isEmpty()) {
                    ctx.getMessages().add(assistant);
                    String answer = text;

                    // ① 空答案重试一次：补一条 user 提示，要求直接给最终答复
                    if (isBlank(answer) && !ctx.isAnswerRetried()) {
                        ctx.setAnswerRetried(true);
                        ctx.getMessages().add(new UserMessage("请基于已有信息给出最终答复，不要调用工具。"));
                        Prompt retryPrompt = new Prompt(ctx.getMessages(), options);
                        ChatResponse retryResponse = callLlm(retryPrompt, ctx.getMaxSteps());
                        AssistantMessage retryMessage = retryResponse.getResult().getOutput();
                        String retryText = retryMessage.getText();
                        if (!isBlank(retryText) && retryMessage.getToolCalls().isEmpty()) {
                            ctx.getMessages().add(retryMessage);
                            ctx.setLastAssistantText(retryText);
                            answer = retryText;
                        }
                    }

                    // ② 仍空 -> 降级：回填最后一条工具结果，status=DEGRADED + errorCode
                    if (isBlank(answer)) {
                        answer = lastToolResult(ctx);
                        ctx.setStatus(TaskStatus.DEGRADED);
                        ctx.setErrorCode("EMPTY_ANSWER");
                    }

                    ctx.setFinalAnswer(isBlank(answer) ? "未能给出答复（模型未返回文本）" : answer);
                    ctx.setState(AgentState.FINISHED);
                    break;
                }

                // 执行 tool_calls 并回填对话历史。
                // call() 在请求线程上计时；executeToolCalls 同步阻塞，返回即本轮工具全部执行完毕，
                // 因此这里立刻 drain() 取走本轮样本（取走即清空）。
                TimeoutToolCallback.clear();   // 双保险：确保本轮从零开始
                ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
                List<TimeoutToolCallback.LatencySample> latencySamples = TimeoutToolCallback.drain();

                ctx.getMessages().clear();
                ctx.getMessages().addAll(execution.conversationHistory());

                Map<String, String> argsById = assistant.getToolCalls().stream()
                        .collect(Collectors.toMap(
                                AssistantMessage.ToolCall::id,
                                AssistantMessage.ToolCall::arguments,
                                (a, b) -> a));

                boolean terminated = false;
                int stepIndex = 0;
                for (ToolResponseMessage.ToolResponse tr : lastToolResponses(execution)) {
                    boolean ok = !isErrorResult(tr.responseData());
                    ctx.getSteps().add(new TraceStep(
                            ctx.getSteps().size() + 1,
                            tr.name(),
                            serverKeyOf(tr.name()),
                            argsById.get(tr.id()),
                            ok,
                            latencyOf(latencySamples, stepIndex, tr.name()),
                            preview(tr.responseData()),
                            ok ? null : tr.responseData(),
                            llmLatencyMs));
                    stepIndex++;
                    if (TerminateTool.TOOL_NAME.equals(tr.name())) {
                        terminated = true;
                    }
                }

                if (terminated) {
                    // 模型调用 terminate 但没给文本时，用最后一条工具结果（terminate 返回"任务结束"）兜底
                    String answer = isBlank(text) ? lastToolResult(ctx) : text;
                    ctx.setFinalAnswer(isBlank(answer) ? "任务已结束" : answer);
                    ctx.setState(AgentState.FINISHED);
                    break;
                }
                // 否则继续下一轮
            }

            if (ctx.getState() == AgentState.RUNNING) {
                ctx.setState(AgentState.FINISHED);
                if (ctx.getSteps().size() >= ctx.getMaxSteps()) {
                    ctx.setStatus(TaskStatus.MAX_STEPS);
                } else {
                    ctx.setStatus(TaskStatus.TIMEOUT);
                }
            }
        } catch (LlmTimeoutException e) {
            ctx.setState(AgentState.ERROR);
            ctx.setStatus(TaskStatus.TIMEOUT);
            ctx.setErrorCode("LLM_TIMEOUT");
            ctx.setErrorMessage("规划 LLM 调用超时（" + props.llmTimeoutSeconds() + "s）");
        } catch (Exception e) {
            ctx.setState(AgentState.ERROR);
            ctx.setStatus(TaskStatus.ERROR);
            ctx.setErrorCode("ORCHESTRATION_ERROR");
            ctx.setErrorMessage(e.getMessage());
        }

        fillFallbackAnswer(ctx);
        TaskTrace trace = buildTrace(ctx);
        traceRecorder.append(trace);   // 成功/失败都落盘（落盘失败只记日志）
        return trace;
    }

    /**
     * 带超时的 LLM 调用：超时则取消任务并抛 {@link LlmTimeoutException}（由 run 统一处理）。
     */
    private ChatResponse callLlm(Prompt prompt, int maxSteps) throws Exception {
        Future<ChatResponse> future = LLM_POOL.submit(() ->
                chatClient.prompt(prompt).system(systemPrompt(maxSteps)).call().chatResponse());
        try {
            return future.get(props.llmTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmTimeoutException();
        }
    }

    /** 规划 LLM 调用超时。 */
    private static final class LlmTimeoutException extends RuntimeException {
    }

    /**
     * 取某个工具调用的耗时：优先按执行顺序配对，名字不一致时按名字查找，兜底 0。
     */
    private static long latencyOf(List<TimeoutToolCallback.LatencySample> samples,
                                  int index, String toolName) {
        if (index < samples.size() && samples.get(index).toolName().equals(toolName)) {
            return samples.get(index).latencyMs();
        }
        return samples.stream()
                .filter(s -> s.toolName().equals(toolName))
                .mapToLong(TimeoutToolCallback.LatencySample::latencyMs)
                .findFirst()
                .orElse(0L);
    }

    private static String systemPrompt(int maxSteps) {
        return String.format(SYSTEM_PROMPT_TEMPLATE, maxSteps);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 取最后一条工具结果预览，作为空答案的兜底。 */
    private static String lastToolResult(TaskContext ctx) {
        List<TraceStep> steps = ctx.getSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            String preview = steps.get(i).resultPreview();
            if (!isBlank(preview)) {
                return preview;
            }
        }
        return "";
    }

    private static List<ToolResponseMessage.ToolResponse> lastToolResponses(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        if (history.isEmpty()) {
            return List.of();
        }
        Message last = history.get(history.size() - 1);
        if (last instanceof ToolResponseMessage toolResponseMessage) {
            return toolResponseMessage.getResponses();
        }
        return List.of();
    }

    /** 工具结果是否是我们包装层/框架产生的错误文本。 */
    private static boolean isErrorResult(String data) {
        if (data == null) {
            return true;
        }
        return data.startsWith("工具调用超时") || data.startsWith("工具调用失败");
    }

    /** 按前缀还原所属 server 名（trace 用）。 */
    private String serverKeyOf(String prefixedToolName) {
        for (Map.Entry<String, String> entry : serverNamesByPrefix.entrySet()) {
            if (prefixedToolName.startsWith(entry.getKey() + "_")) {
                return entry.getValue();
            }
        }
        return "unknown";
    }

    private static String preview(String text ) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static void fillFallbackAnswer(TaskContext ctx) {
        String answer = ctx.getFinalAnswer();
        if (answer != null && !answer.isBlank()) {
            return;
        }
        if (ctx.getStatus() == TaskStatus.OK) {
            answer = ctx.getLastAssistantText();
        } else {
            answer = "未能完成任务（已执行 " + ctx.getSteps().size() + " 步，状态：" + ctx.getStatus() + "）";
        }
        ctx.setFinalAnswer(answer == null ? "" : answer);
    }

    private TaskTrace buildTrace(TaskContext ctx) {
        Instant finished = Instant.now();
        return new TaskTrace(
                ctx.getTaskId(),
                ctx.getTask(),
                ctx.getStatus(),
                props.model(),
                ctx.getMaxSteps(),
                ctx.getSteps().size(),
                ctx.getStartedAt().toString(),
                finished.toString(),
                Duration.between(ctx.getStartedAt(), finished).toMillis(),
                ctx.getFinalAnswer(),
                List.copyOf(ctx.getSteps()),
                null,   // degraded：M2 未实现
                ctx.getErrorCode(),
                ctx.getErrorMessage(),
                null,   // tokenUsage：M2 未采集
                null);  // retryCount：M2 未实现
    }
}
