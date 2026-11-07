# 代码走读：按调用链顺一遍

> 用途：复盘 / 面试前过一遍 / 被追问时能定位到文件与行。
> 顺序就是**一次请求的流转顺序**，不是目录顺序。每段代码前有一句“这段解决什么问题”，后有一句“可能被问什么”。

## 0. 文件地图

| 层 | 文件 | 职责 |
|---|---|---|
| 入口 | `web/TaskController.java` | HTTP 边界：校验、生成 taskId、建上下文 |
| 编排 | `agent/TaskOrchestrator.java` | 状态机：规划 → 执行 → 观察 → 汇总（无状态） |
| 上下文 | `agent/TaskContext.java` | 单请求可变状态（消息、步骤、状态、错误码） |
| 包装 | `agent/CircuitBreakingToolCallback.java` | 最外层：熔断判断、快速拒绝 |
| 包装 | `agent/TimeoutToolCallback.java` | 内层：45s 超时、失败归类 |
| 旁路 | `agent/ToolCallRecorder.java` | 结构化记录成败/耗时（ThreadLocal） |
| 熔断 | `resilience/CircuitBreaker.java` | 三态状态机（纯类，注入 Clock） |
| 熔断 | `resilience/CircuitBreakerRegistry.java` | 按 server 管理 + 版本号 |
| 熔断 | `resilience/CircuitBreakerProperties.java` | `mcp.circuit.*` |
| 模型 | `agent/model/TaskTrace.java`、`TraceStep.java` | 响应体 / trace 结构 |
| 落盘 | `agent/trace/TraceRecorder.java` | JSONL，按日期切分 |
| 目录 | `web/CatalogController.java` | 给模型看的工具清单（前缀化） |
| 健康 | `DemoController.java` | `/api/servers` 带熔断状态 |
| 工具服务 | `demo/py-mcp-server/server.py` | `get_time` / `add` / `subtract` |
| Agent 服务 | `demo/py-agent/agent.py` | `summarize` / `translate` / `run_task` / `ping` |
| 评测 | `demo/eval/run_eval.py` | 批量跑 + 打分 + 报告 |

---

## 1. 入口：TaskController

```java
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

public record TaskRequest(String task, Integer maxSteps) { }
```

**解决什么**：把 Web 层的东西（校验、ID、参数夹取）留在边界，编排器只处理“任务”。

**可能被问**：为什么 maxSteps 要 clamp 到 1~20？——防止恶意/误传导致长循环把线程占死；上限也就是“步数兜底”。

---

## 2. 编排器构造：工具是怎么被“包”起来的

```java
// 先建“前缀 -> server 名”映射：给工具包熔断器时要按 server 取熔断器
Map<String, String> serverNames = new LinkedHashMap<>();
for (McpSyncClient client : mcpClients) {
    McpSchema.Implementation info = client.getServerInfo();
    if (info != null && info.name() != null) {
        serverNames.put(McpToolPrefixConfig.sanitize(info.name()), info.name());
    }
}
this.serverNamesByPrefix = Map.copyOf(serverNames);

// MCP 工具：前缀化 → 超时包装 → 熔断包装（熔断必须在外层，超时才算失败）
ToolCallback[] mcpTools = new SyncMcpToolCallbackProvider(
        (connection, tool) -> true,
        mcpToolNamePrefixGenerator,
        mcpClients.toArray(McpSyncClient[]::new)).getToolCallbacks();

List<ToolCallback> allTools = new ArrayList<>();
for (ToolCallback callback : mcpTools) {
    String prefixedName = callback.getToolDefinition().name();
    ToolCallback withTimeout = new TimeoutToolCallback(callback, props.toolTimeoutSeconds());
    allTools.add(new CircuitBreakingToolCallback(
            withTimeout, circuitBreakers.get(serverKeyOf(prefixedName))));
}
// 本地终止工具（与 MCP 无关，不加前缀、不包装超时/熔断）
allTools.addAll(List.of(MethodToolCallbackProvider.builder()
        .toolObjects(new TerminateTool())
        .build()
        .getToolCallbacks()));
this.tools = allTools.toArray(ToolCallback[]::new);
```

**解决什么**：三件事——① 按 server 建熔断器映射；② 每个 MCP 工具套两层装饰器；③ 本地 `terminate` 不套（它不依赖网络）。

**可能被问**：
- 为什么熔断在外层？——外层先判断，拒绝时不会进入超时器，才能“0ms 快速失败”；反过来超时在外层会把内层快速失败也包住，而且超时不算进熔断。
- 为什么 `terminate` 不包装？——它不调外部服务，没有超时/熔断语义；但它会出现在 trace 里，评测时要剔除（见第 10 节）。

---

## 3. 主循环：规划 → 执行 → 观察

```java
Instant deadline = ctx.getStartedAt().plus(Duration.ofSeconds(props.taskTimeoutSeconds()));
try {
    while (ctx.getState() == AgentState.RUNNING
            && ctx.getSteps().size() < ctx.getMaxSteps()
            && Instant.now().isBefore(deadline)) {

        ToolCallRecorder.clear();          // 每轮清空旁路记录

        long llmStart = System.nanoTime();
        Prompt prompt = new Prompt(ctx.getMessages(), options);
        ChatResponse response = callLlm(prompt, ctx.getMaxSteps());
        long llmLatencyMs = (System.nanoTime() - llmStart) / 1_000_000;

        AssistantMessage assistant = response.getResult().getOutput();
        String text = assistant.getText();

        // 不再调用工具 -> 结束
        if (assistant.getToolCalls().isEmpty()) {
            ...
            break;
        }

        // 执行 tool_calls 并回填对话历史
        ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
        List<ToolCallRecorder.CallRecord> callRecords = ToolCallRecorder.drain();

        ctx.getMessages().clear();
        ctx.getMessages().addAll(execution.conversationHistory());
        ...
    }
}
```

**解决什么**：这就是“手动 ReAct 循环”。`internalToolExecutionEnabled(false)` 让框架**不要**自动执行工具，改由我们显式调用 `executeToolCalls`，从而能在中间插入 trace、超时、熔断。

**可能被问**：
- 为什么不用 Spring AI 的自动工具执行？——自动执行拿不到“每一步的成败/耗时/被熔断”这些结构化信息，trace 和降级判定都做不了。
- 循环的退出条件？——无 tool_calls（正常结束）、步数上限、任务 deadline、`terminate`、异常。

---

## 4. 每步的 trace 记录：成败来自“事实”而不是“文本”

```java
boolean terminated = false;
int stepIndex = 0;
for (ToolResponseMessage.ToolResponse tr : lastToolResponses(execution)) {
    // 成败来自包装层记录的事实（超时/异常），不再扫描错误文本
    ToolCallRecorder.CallRecord record = recordOf(callRecords, stepIndex, tr.name());
    ctx.getSteps().add(new TraceStep(
            ctx.getSteps().size() + 1,
            tr.name(),
            serverKeyOf(tr.name()),
            argsById.get(tr.id()),
            record.ok(),
            record.latencyMs(),
            preview(tr.responseData()),
            record.ok() ? null : tr.responseData(),
            record.errorCode(),
            llmLatencyMs));
    stepIndex++;
    if (TerminateTool.TOOL_NAME.equals(tr.name())) {
        terminated = true;
    }
}
```

配对的取值函数：

```java
private static ToolCallRecorder.CallRecord recordOf(List<ToolCallRecorder.CallRecord> records,
                                                   int index, String toolName) {
    if (index < records.size() && records.get(index).toolName().equals(toolName)) {
        return records.get(index);
    }
    return records.stream()
            .filter(r -> r.toolName().equals(toolName))
            .findFirst()
            .orElseGet(() -> new ToolCallRecorder.CallRecord(toolName, 0L, true, null));
}
```

**解决什么**：把“这一步成没成功”变成结构化事实（`ok` + `errorCode` + `latencyMs`），trace 才能用于统计。

**可能被问**：为什么不用错误文本前缀判断？——文案一改判定就失效；而且 MCP 协议本身有 `isError`，Spring AI 会抛 `ToolExecutionException`，用异常类型判断更可靠。

---

## 5. LLM 调用超时：用 Future 包一层

```java
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
```

**解决什么**：`ChatClient` 没有同步超时参数，用线程池 + `Future.get(timeout)` 兜一层，避免请求卡死。

**可能被问**：`cancel(true)` 能真正杀掉请求吗？——只能中断线程，HTTP 客户端未必响应中断，所以这是“尽力而为”的超时；真正的资源回收靠连接池超时。

---

## 6. 降级判定：任一步失败且仍有答案 → DEGRADED

```java
static void markDegradedIfNeeded(TaskContext ctx) {
    if (ctx.getStatus() != TaskStatus.OK) {
        return;
    }
    List<TraceStep> failed = ctx.getSteps().stream().filter(step -> !step.ok()).toList();
    if (failed.isEmpty()) {
        return;
    }
    TraceStep first = failed.get(0);
    ctx.setStatus(TaskStatus.DEGRADED);
    ctx.setErrorCode(first.errorCode());
    ctx.setErrorMessage("第 " + first.step() + " 步工具调用失败（" + first.errorCode() + "）");
    ctx.setDegradedSteps(failed.stream().map(TraceStep::step).toList());
}
```

**解决什么**：让“带伤产出”和“健康产出”区分开。`OK` 因此蕴含“所有步骤都成功”，成功率口径才干净。

**可能被问**：为什么取第一个失败步骤的码？——第一个通常是根因，后面的失败往往是它的连锁反应。

---

## 7. Trace 组装与落盘

```java
private TaskTrace buildTrace(TaskContext ctx) {
    Instant finished = Instant.now();
    return new TaskTrace(
            ctx.getTaskId(), ctx.getTask(), ctx.getStatus(), props.model(),
            ctx.getMaxSteps(), ctx.getSteps().size(),
            ctx.getStartedAt().toString(), finished.toString(),
            Duration.between(ctx.getStartedAt(), finished).toMillis(),
            ctx.getFinalAnswer(), List.copyOf(ctx.getSteps()),
            ctx.getStatus() == TaskStatus.DEGRADED,
            ctx.getErrorCode(), ctx.getErrorMessage(),
            List.copyOf(ctx.getDegradedSteps()),
            null, null);
}
```

```java
public synchronized void append(TaskTrace trace) {
    try {
        String line = objectMapper.writeValueAsString(trace) + System.lineSeparator();
        Path file = traceDir.resolve(LocalDate.now() + ".jsonl");
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
        log.warn("trace 落盘失败: taskId={}", trace.taskId(), e);
    }
}
```

**解决什么**：一次请求一行 JSONL，按天切文件，方便脚本聚合；落盘失败不影响任务。

**可能被问**：并发下会写乱吗？——`append` 加了 `synchronized`；同一进程内串行写。多实例部署就不行了，需要按实例分文件。

---

## 8. 超时包装器：TimeoutToolCallback

```java
public String call(String toolInput) {
    String toolName = delegate.getToolDefinition().name();
    long start = System.nanoTime();
    Future<String> future = null;
    try {
        future = POOL.submit(() -> delegate.call(toolInput));
        String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
        record(toolName, start, true, null);
        return result;
    } catch (TimeoutException e) {
        if (future != null) future.cancel(true);
        record(toolName, start, false, ToolCallRecorder.TOOL_TIMEOUT);
        return "工具调用超时（" + timeoutSeconds + "s），未返回结果。";
    } catch (Exception e) {
        if (future != null) future.cancel(true);
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        record(toolName, start, false, ToolCallRecorder.TOOL_ERROR);
        return "工具调用失败：" + rootMessage(e);
    }
}
```

**解决什么**：工具调用限时；失败归类为 `TOOL_TIMEOUT` / `TOOL_ERROR`；错误文本回填给模型让它换工具。

**可能被问**：为什么异常不往外抛？——抛出去会被 `ToolCallingManager` 包一层，模型拿到的还是文本；所以我们自己转成文本，同时把结构化结果记到旁路，两不耽误。

---

## 9. 熔断包装器：CircuitBreakingToolCallback

```java
public String call(String toolInput) {
    String toolName = getToolDefinition().name();

    if (!breaker.tryAcquire()) {
        ToolCallRecorder.record(toolName, 0L, false, ToolCallRecorder.CIRCUIT_OPEN);
        return "服务熔断中（" + breaker.serverKey() + " 连续失败），本次调用已快速拒绝，"
                + "请稍后重试或改用其他工具。";
    }

    int before = ToolCallRecorder.size();
    try {
        String result = delegate.call(toolInput);
        // 只认“本次调用之后新增的”记录：历史失败记录不能冒充本次结果
        ToolCallRecorder.CallRecord record = ToolCallRecorder.since(before, toolName);
        if (record != null && !record.ok()) {
            breaker.onFailure();
        } else {
            breaker.onSuccess();
        }
        return result;
    } catch (RuntimeException e) {
        breaker.onFailure();
        ToolCallRecorder.record(toolName, 0L, false, ToolCallRecorder.TOOL_ERROR);
        return "工具调用失败：" + e.getMessage();
    }
}
```

**解决什么**：调用前问熔断器“能不能调”；调用后把成败喂回去。拒绝路径完全不碰内层，所以是 0ms。

**可能被问**：`before` / `since` 是干嘛的？——初版用“最近一条记录”判断成败，结果上一轮的失败记录冒充了本次成功，导致熔断器永远关不上；改成“只看本次调用之后新增的记录”。

---

## 10. 三态状态机：CircuitBreaker

```java
public synchronized boolean tryAcquire() {
    if (state == CircuitState.OPEN) {
        if (Instant.now(clock).isBefore(openUntil)) {
            return false;          // 冷却中，快速拒绝
        }
        enterHalfOpen();           // 冷却到期，下一个请求触发半开
    }
    if (state == CircuitState.HALF_OPEN) {
        if (halfOpenPermits <= 0) {
            return false;          // 探测额度用完，其余请求继续拒绝
        }
        halfOpenPermits--;
    }
    return true;
}

public synchronized void onSuccess() {
    if (state == CircuitState.HALF_OPEN) {
        halfOpenSuccesses++;
        if (halfOpenSuccesses >= props.halfOpenPermits()) {
            close();
        }
        return;
    }
    if (state == CircuitState.CLOSED) {
        consecutiveFailures = 0;
    }
}

public synchronized void onFailure() {
    Instant now = Instant.now(clock);
    if (state == CircuitState.HALF_OPEN) {
        open(now);                 // 探测失败：立刻回 OPEN，冷却重新计时
        return;
    }
    if (state == CircuitState.CLOSED) {
        consecutiveFailures++;
        if (consecutiveFailures >= props.failureThreshold()) {
            open(now);
        }
    }
}
```

状态迁移（计数只在切换时清零）：

```java
private void enterHalfOpen() {
    consecutiveFailures = 0;
    halfOpenSuccesses = 0;
    halfOpenPermits = props.halfOpenPermits();
    transitionTo(CircuitState.HALF_OPEN);
}

private void open(Instant now) {
    openUntil = now.plusSeconds(props.openSeconds());
    halfOpenPermits = 0;
    halfOpenSuccesses = 0;
    transitionTo(CircuitState.OPEN);
}

private void close() {
    consecutiveFailures = 0;
    halfOpenSuccesses = 0;
    halfOpenPermits = 0;
    openUntil = null;
    transitionTo(CircuitState.CLOSED);
}
```

**解决什么**：连续失败 3 次打开、冷却 30s、半开只放 1 个探测；注入 `Clock` 让测试能拨时间。

**可能被问**：
- 为什么不用定时器？——冷却到期由“下一个真实请求”触发切换，省一个线程；代价是没有流量时状态一直显示 `OPEN`（语义上也算对）。
- 为什么并发额度是 1？——探测请求越少，越不容易把刚恢复的服务再打挂。
- 半开成功要几次才关闭？——`halfOpenPermits` 次，默认 1；配置化。

---

## 11. 注册表：按 server 管熔断器

```java
public CircuitBreaker get(String serverKey) {
    return breakers.computeIfAbsent(serverKey,
            key -> new CircuitBreaker(key, props, clock, version::incrementAndGet));
}

public long version() {
    return version.get();
}
```

**解决什么**：同一 server 的工具共用一个熔断器；状态一变版本号 +1，供“重建工具表”使用（当前版本没启用重建，见下）。

**可能被问**：版本号现在有人用吗？——`CircuitBreakerRegistry.version()` 已实现，但“按版本重建工具表”没做：因为一旦把熔断中的工具从目录摘除，就再也收不到请求，也就永远无法半开恢复。所以现在只做“调用时拒绝”。

---

## 12. 工具目录：CatalogController

```java
@GetMapping("/catalog")
public List<Map<String, String>> catalog() {
    return Arrays.stream(callbackProvider.getToolCallbacks())
            .map(ToolCallback::getToolDefinition)
            .map(def -> Map.of(
                    "name", def.name(),
                    "description", def.description() == null ? "" : def.description(),
                    "inputSchema", def.inputSchema() == null ? "{}" : def.inputSchema()))
            .toList();
}
```

**解决什么**：把“模型实际看到的工具”暴露出来调试——名字是前缀化的、描述是原样的。

**可能被问**：和 `/api/tools` 区别？——`/api/tools` 是原始工具（server 视角，无前缀），`/api/catalog` 是模型视角（前缀 + schema）。

---

## 13. Python 工具服务：server.py

```python
mcp = MCPServer("demo-tools")

@mcp.tool()
def get_time() -> str:
    """返回服务器当前时间（ISO 8601 格式）。"""
    return datetime.now().isoformat(timespec="seconds")

@mcp.tool()
def add(a: float, b: float) -> float:
    """计算两个数字之和。"""
    return a + b
```

**解决什么**：用装饰器把函数注册成 MCP 工具；`@mcp.tool()` 会读类型注解生成 JSON Schema。

**可能被问**：函数签名怎么变成 schema 的？——SDK 用 pydantic 从类型注解和默认值生成 `inputSchema`；参数名、类型、默认值都进 schema。

---

## 14. Python Agent：能力工具集与描述

```python
SUMMARIZE_DESCRIPTION = """把一段文本压缩成要点/摘要。仅用于「摘要、概括、提炼、压缩」类需求。

输入：
- text：需要压缩的原文（一段完整文本）
- max_words：输出长度上限，默认 100；中文按字计、英文按词计

输出：紧凑的要点或摘要，只包含原文已有的信息，语言与原文一致（不翻译）。

不做什么（以下需求不要调用本工具）：
- 不翻译文本（翻译请用 translate）
- 不做润色、改写风格、续写、扩写
- 不查询资料、不计算、不联网、不核实事实真伪
- 不处理图片、URL、文件，只处理传入的文本"""
```

```python
@mcp.tool(title="文本摘要", description=SUMMARIZE_DESCRIPTION)
def summarize(text: str, max_words: int = 100) -> str:
    user_prompt = (
        f"请把下面的文本压缩成要点，总长度不超过 {max_words} 个字（英文按词计）。"
        "只保留关键信息，不要添加原文没有的内容。"
        "输出语言必须与原文一致（中文原文用中文输出），不要翻译。"
        "文本较长时用 3~5 条要点，较短时用一句话概括；直接给结果，不要开场白。\n\n"
        f"文本：\n{text}"
    )
    return _ask_llm(SUMMARIZER_SYSTEM_PROMPT, user_prompt)
```

**解决什么**：工具描述是“给模型看的接口文档”。写清“不做什么”比写“做什么”更能防错选；用常量而不是 docstring，避免 `__doc__` 的缩进污染发出去的文本。

**可能被问**：为什么不用 docstring？——MCP SDK 取的是原始 `__doc__`，函数体缩进会带进描述，模型看到的是一堆前导空格；改用显式 `description=` 更可控。

---

## 15. 评测脚本：run_eval.py 的核心三函数

```python
CONTROL_TOOLS = {"terminate"}   # 控制动作，不参与“能力选择”评分

def split_control_actions(tools: list) -> tuple:
    return ([t for t in tools if t not in CONTROL_TOOLS],
            [t for t in tools if t in CONTROL_TOOLS])

def score(run: dict, expected: list, mode: str) -> dict:
    """按金标准打分。direct 组没有工具，只统计“是否给出答案”。"""
    actual = run["tools"]
    answered = bool((run["answer"] or "").strip())
    if mode == "direct":
        return {"strict": None, "lenient": None, "answered": answered,
                "missing": [], "extra": [], "toolsMatch": None}

    tools_match = actual == expected
    strict = run["status"] == "OK" and tools_match and answered
    if expected:
        lenient = answered and all(t in actual for t in expected)
    else:
        lenient = answered and actual == []
    return {
        "strict": strict, "lenient": lenient, "answered": answered,
        "missing": [t for t in expected if t not in actual],
        "extra": [t for t in actual if t not in expected],
        "toolsMatch": tools_match,
    }
```

```python
def rescore(out_dir: Path, sample: int, seed: int) -> int:
    """只重算指标（不重新调用 API），用于口径调整后复算历史数据。"""
    manifest = json.loads((out_dir / "manifest.json").read_text(encoding="utf-8"))
    records = [json.loads(line) for line in (out_dir / "raw.jsonl").read_text(encoding="utf-8").splitlines()
               if line.strip()]
    rows = [make_row(rec["task"], rec["run"], manifest["group"]) for rec in records]
    write_outputs(out_dir, manifest, rows, manifest["group"], sample, seed)
```

**解决什么**：严格/宽松两种成功率；剔除控制工具；改口径时用 `--rescore` 复算而不是重跑模型（保证对比公平）。

**可能被问**：为什么剔除 `terminate`？——它是编排层的结束动作，不是“能力选择”；不剔除会把正确拒绝的任务判成失败（o3/o4）。
