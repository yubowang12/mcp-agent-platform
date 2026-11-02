# 编排层架构设计：规划型 Host（方案 B）

> 项目：基于 MCP 协议的微服务化多智能体协作平台设计与实现
> 状态：实现中（M1~M5 已落地，2026-11-02 全量测试 24/24 通过；M6 待实现）
> 前置：阶段 0 技术验证已完成（见 `tech-validation-report.md`）
> 本文取代"哑编排"形态，作为正式实现的设计基线

## 一、为什么从哑编排转向规划型 Host

阶段 0 验证的链路是 `Java HTTP → MCP → Python Agent → Ollama`，但 Java 层**没有任何决策**：`task` 原样转发给 `agent-worker`，Agent 收到后单轮调用一次 LLM 就返回。问题有三：

1. **没有"编排"可言**：整条链路只是 RPC 顺序调用，"多智能体协作"退化为"一个 Agent 转调一次模型"，论文无从展开。
2. **没有调度者，治理层无处落地**：注册发现、健康检查、熔断、限流都需要一个"决定把任务交给谁"的组件，否则只是给端口加了配置。
3. **MCP 协议只用了 1/3**：只用 `tools/call`，Host 没有扮演 Host 的角色。

**决策**：编排层升级为**规划型 Host**——自带 LLM，负责规划、调度、聚合；Python Agent 退回**执行器**角色，负责把单个子任务做好。

**保留方案 A（哑编排）作为实验对照组**，不删除。

## 二、角色映射

| MCP 角色 | 定义 | 本项目 |
|---|---|---|
| **Host** | 协调一个或多个 Client 的 AI 应用 | Java 编排层（8090），**内含规划 LLM** |
| **Client** | Host 内为每个 Server 维护的连接 | `List<McpSyncClient>` / `McpAsyncClient` |
| **Server** | 暴露能力的一方 | `demo-tools`(8001) / `agent-worker`(8002) / 后续更多 Agent |

> 说明：阶段 0 是"Agent-as-Server 变体"（LLM 在 Agent 内）。本设计回到规范的标准形态（LLM 在 Host），Agent 内部**仍可保留**自己的领域模型调用，但不再承担"决定做什么"的职责，只承担"把这一步做好"。
>
> `sampling`（Server 反向借 Host 的模型）本阶段**不实现**，列为扩展工作，见第九节。

> 官方文档：[MCP 是什么](https://modelcontextprotocol.io/docs/2026-07-28/learn/architecture#concepts-of-mcp)

## 三、总体架构

```
                         ┌─────────────────────────────────────────────┐
   POST /api/task        │           Java 编排层（Host, 8090）          │
   {"task":"...",        │                                             │
    "maxSteps":5}  ────▶ │  ┌──────────┐  ┌──────────┐  ┌───────────┐ │
                         │  │ Catalog  │  │ Planner  │  │Aggregator │ │
                         │  │ 工具目录  │  │ 规划 LLM │  │ 结果综合   │ │
                         │  └────┬─────┘  └────┬─────┘  └─────┬─────┘ │
                         │       │             │              │       │
                         │  ┌────▼─────────────▼──────────────▼────┐  │
                         │  │        Orchestrator（状态机）         │  │
                         │  │   PLANNING → EXECUTING → OBSERVE     │  │
                         │  │      ↑______________________|        │  │
                         │  │  终止：无tool_calls / 步数上限 / 超时  │  │
                         │  └────┬──────────────────────┬─────────┘  │
                         │       │ McpAsyncClient       │            │
                         │  ┌────▼─────┐           ┌────▼─────┐      │
                         │  │ demo-tools│          │agent-worker│    │
                         │  │ (8001)    │          │ (8002)     │    │
                         │  └───────────┘           └────┬──────┘    │
                         └───────────────────────────────┼──────────┘
                                                         │ OpenAI 兼容
                                                         ▼
                                              Ollama qwen2.5:3b（执行）
                                              Ollama qwen2.5:7b（规划）
```

## 四、编排层内部设计

### 4.1 Catalog：工具目录（MCP 的 tools/list）

职责：

1. 启动时遍历 `List<McpSyncClient>`，逐个 `listTools()`，合并成全局目录；
2. **名称前缀化**（见 4.2），生成给模型看的稳定工具名；
3. 缓存，并监听 `McpToolsChangedEvent`（框架事件）与 `notifications/tools/list_changed` 失效缓存；
4. 供 `/api/catalog` 调试接口直接返回（JSON，人类可读）。

```java
record CatalogEntry(String serverName, String toolName, String description,
                    McpSchema.JsonSchema inputSchema, boolean healthy) {}
```

### 4.2 命名空间：必须用下划线，不能用点号

**约束来源**：function calling 的工具名必须匹配 `^[a-zA-Z0-9_-]{1,64}$`（OpenAI 兼容实现，Ollama 亦然）。点号、空格、中文都非法。


方案：`<serverKey>_<toolName>`，`serverKey` 取自 `application.yml` 里 `connections` 的 key（权威身份），**不取** `serverInfo.name`（对端自报，不可信）。

```
demo-tools    → demotools_add、demotools_get_time
agent-worker  → agentworker_run_task、agentworker_ping
```

实现：注册一个 `McpToolNamePrefixGenerator` bean（框架官方钩子，已核实存在于 `spring-ai-mcp:1.1.8` [官方文档](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-client-boot-starter-docs.html#_tool_name_prefix_generation)）：

```java
@Bean
McpToolNamePrefixGenerator prefixGenerator() {
    return (conn, tool) -> sanitize(conn.serverKey()) + "_" + tool.name();
}
```

同时保留**双向映射表**：给模型的 `demotools_add` ↔ 路由时还原成 `(demo-tools, add)`。**绝不把前缀发给 Server**（Server 不认识）。

### 4.3 工具过滤（工具表爆炸的解药）

3B/7B 模型看到几十个工具会选错。用框架提供的 `McpToolFilter` 做两级收敛：

- **按域分组**（默认）：按 `serverKey` 分组，提示词里先让模型选"领域"，再加载该域工具；
- **按健康度过滤**：熔断中的 Agent 从目录里摘除（见 4.6）。

第一版可以先不做分组，只保留"健康度过滤"，把分组列为可选优化。

> 官方文档：[工具过滤](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html#_tool_filtering)

### 4.4 Planner：规划循环

用 Spring AI `ChatClient` + 注入的 `ToolCallbackProvider`（框架的 `SyncMcpToolCallbackProvider` 会把每个 MCP 工具转成 `ToolCallback`）。

> 已核实：`spring.ai.mcp.client.toolcallback.enabled=true` 时，`McpToolCallbackAutoConfiguration` 自动注册 `SyncMcpToolCallbackProvider` 与 `AsyncMcpToolCallbackProvider`。**具体注入方法名以实际 jar 为准**（ChatClient.Builder 的 `defaultTools` / `defaultToolCallbacks`）。


系统提示词（与 `TaskOrchestrator.SYSTEM_PROMPT_TEMPLATE` 同步，2026-10-28）：

```
你是 Agent 集群的调度器。任务：
1) 把用户任务拆成最小可执行步骤；
2) 每步只选一个工具，参数必须符合该工具的 schema；
3) 信息足够时立即停止调用，直接给出最终答复；
4) 工具描述里写明了能力边界，越界的需求不要硬套工具，直接说明原因；
5) 任务完成或无法继续推进时，调用 terminate 工具结束任务。

规则：
- 每步只能调一个工具，不要并行猜测多个；
- 调用前先确认参数齐全；缺参数则向用户追问；
- 最多 {maxSteps} 步，超出则基于已有信息作答。
- 自己能完成的写作、解释、建议类任务，直接回答，不要调用工具
- 最终答复只给结果，不要复述输入
```

> 实现备注（2026-10-28，M4）：最后两条规则是 M4 的关键修正。没有它们时，"写一份自我介绍"这类纯生成任务会被委派给 `run_task`，一次任务变成 3 次模型调用（7b 规划 → 3b 执行 → 7b 收尾）、耗时 35s，且最终答案由更弱的执行模型产出；加上后同一条任务变成 1 次 7b 调用、16.5s，且不再编造个人信息。数据见 `development-protocol.md` 的 M4 验收记录。

**关键**：规划器能看到的只有工具 `description` 与 `inputSchema`。所以**工具描述的质量直接决定规划质量**——见第六节。

### 4.5 Orchestrator：状态机与并发

```
                    ┌─────────────────────────────────────────┐
                    │                                         │
  START → PLANNING ─┤ 有 tool_calls → EXECUTING → OBSERVE ────┤→ PLANNING
                    │ 无 tool_calls → ANSWERING → DONE        │
                    └─────────────────────────────────────────┘
  终止条件（任一满足即退出）：
    1. 模型返回纯文本答复（无 tool_calls）
    2. 步数 ≥ maxSteps（默认 10）
    3. 总耗时 ≥ deadline（默认 300s）
    4. 规划 LLM 报错 / 返回非法结构（进入降级，见 4.7）
```

**并发与超时**（必须做，否则一个慢 Agent 卡死整个请求）：

- 同一轮返回多个 `tool_calls` 时，用 `McpAsyncClient` + `CompletableFuture` 并行执行；
- 单次 `tools/call` 超时 45s（Agent 调 LLM 的实际耗时），总 deadline 120s；
- 规划 LLM 调用超时 60s。

> 官方文档：[添加官方文档内容]

### 4.6 Trace：执行轨迹（实验数据来源）

每次请求落一条结构化 trace，**这是论文实验章节的原始数据**：

```json
{
  "taskId": "t-0001",
  "task": "...",
  "startedAt": "...", "finishedAt": "...",
  "steps": [
    {"step": 1, "tool": "agentworker_run_task", "args": {...},
     "ok": true, "latencyMs": 1832, "resultPreview": "..."},
    {"step": 2, "tool": "demotools_add", "args": {...},
     "ok": true, "latencyMs": 12, "resultPreview": "4.0"}
  ],
  "finalAnswer": "...",
  "status": "OK | MAX_STEPS | TIMEOUT | DEGRADED",
  "totalLatencyMs": 4012
}
```

落盘位置：`demo/logs/trace/`（JSONL，一行一条），便于脚本聚合。

### 4.7 降级策略

| 触发 | 行为 |
|---|---|
| 规划 LLM 超时/报错 | 回退到**规则编排**：按关键词路由（如任务含"时间"→`get_time`），命中则执行，否则直接返回错误说明 |
| 某 Agent 连续 N=3 次失败 | 熔断打开：调用被**毫秒级拒绝**（`errorCode=CIRCUIT_OPEN`），`/api/servers` 标 `healthy=false`；冷却 30s 后由下一个请求半开探测 |
| 单步工具失败 | 把错误信息回填给规划 LLM，让它换工具或放弃，**不直接抛 500**；trace 记 `errorCode=TOOL_ERROR` |
| 模型反复调同一工具 | 步数上限兜底，并记录 trace 标记 `status=MAX_STEPS` |

> 实现备注（2026-11-02，M5）：**不摘除工具**（与 4.3 的"健康度过滤"不同）——因为半开探测是惰性的（冷却到期由下一个真实请求触发），一旦把工具摘掉，就再也收不到请求、永远无法恢复。因此改为"目录照常列出 + 调用时快速拒绝"。代价是模型可能撞一次墙再换工具。
>
> 已知限制：Python 服务**重启后**，Java 侧持有的 MCP 会话失效（`MCP session with server terminated`），半开探测必然失败，状态停在 `OPEN`，需要重启编排层才能恢复。重连（重建 `McpSyncClient`）留到 R1。

## 五、接口约定（HTTP）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/servers` | 已连接的 MCP Server（保留现状） |
| GET | `/api/catalog` | 前缀化工具目录（供调试与实验记录） |
| GET | `/api/tools` | 原始工具列表（保留，兼容阶段 0 脚本） |
| POST | `/api/task` | **主入口**：`{"task":"...","maxSteps":5}` → 异步执行，返回 trace |
| POST | `/api/agent/run` | 保留阶段 0 直通接口（对照组 A） |
| GET | `/api/agent/ping` | 健康检查（保留） |

`/api/task` 请求/响应：

```jsonc
// 请求
{ "task": "现在几点？再算一下 1.5+2.5", "maxSteps": 5, "plannerModel": "qwen2.5:7b" }

// 响应
{
  "taskId": "t-0001",
  "status": "OK",
  "answer": "现在是 2026-10-19T10:30:00，1.5+2.5=4.0",
  "trace": { "steps": [...] , "totalLatencyMs": 4012 }
}
```

同步返回即可（单次任务通常几秒），后续若要"长任务"再上 SSE 进度推送。

## 六、Agent 侧设计（执行器）

### 6.1 能力结构化：从"万能入口"到"能力工具集"

现状 `run_task(task: str)` 对规划模型是**零信息量**的签名，模型只能盲选。改为：

| 工具 | 签名 | 说明（中文，写清边界） |
|---|---|---|
| `summarize` | `summarize(text: str, max_words: int = 100) -> str` | 把长文本压缩为要点；**不**做翻译、不做查询 |
| `translate` | `translate(text: str, target_lang: str) -> str` | 中英互译；**不**做润色、不做摘要 |
| `run_task` | `run_task(task: str) -> str` | 兜底：通用自然语言任务，仅在无专用工具匹配时使用 |

原则：

1. **一个工具一件事**，名字是动词短语；
2. **description 必须写"不做什么"**——这比写"做什么"更能防错选；
3. 参数尽量带默认值（`max_words=100`），减少缺参失败；
4. 保留 `run_task` 作为 fallback，但 description 明确标"通用兜底"；
5. **规划器优先**：能由规划器自己完成的写作、解释、建议类任务，不委派给执行器（2026-10-28 M4 实测，见 4.4 实现备注）。

实现状态（2026-10-28，M4 完成）：`summarize` / `translate` / `run_task` / `ping` 均已落地；`title` 与描述用显式参数传入（不用 docstring），避免 `__doc__` 缩进污染发给模型的文本。5 条样本（时间 / 两步算术 / 摘要 / 翻译 / 写作）工具选择全部命中，详见 `development-protocol.md` M4 验收记录。

### 6.2 模型分工

| 角色 | 模型 | 理由 |
|---|---|---|
| 规划器（Host） | `qwen2.5:7b` | 任务分解与工具选择是"难"能力，3b 易跑偏 |
| 执行器（Agent） | `qwen2.5:7b`（2026-10-28 起；可用 `LLM_MODEL` 切回 3b） | 3b 在摘要长度约束与"输出语言锁定"上不稳定（短文本会整段翻译成英文） |

两者均通过配置项切换，不写死（便于实验对比"全 3b / 全 7b / 双层"）。注意：改成双 7b 后，`B-dual` 对照组需要把 `LLM_MODEL=qwen2.5:3b` 显式设回去，M6 实验前统一定标。

## 七、里程碑（每个里程碑可独立验证）

| # | 内容 | 验证方式 | 状态 |
|---|---|---|---|
| M1 | 依赖与装配：加 `spring-ai-starter-model-ollama`、`toolcallback.enabled=true`、前缀 bean | `/api/catalog` 返回 `demotools_add` 等前缀化工具；日志显示模型可用 | ✅ 完成（2026-10-22） |
| M2 | 单步闭环：`/api/task` 能完成"现在几点"这类单工具任务 | 返回正确时间，trace 只有 1 步 | ✅ 完成（2026-10-22） |
| M3 | 多步编排 + trace：完成"先算…再…"两步任务 | trace 中 ≥2 步且顺序合理 | ✅ 完成（2026-10-25，含 JSONL 落盘 + 空答案降级 + 超时配置化；2026-10-28 回归 3/3 通过） |
| M4 | 能力工具集：Agent 加 `summarize`/`translate`，模型能选中专用工具而非 `run_task` | "把这段话压缩成要点"命中 `agentworker_summarize` | ✅ 完成（2026-10-28：5/5 命中、`mvn test` 4/4；含"写作类任务由规划器直答"修正） |
| M5 | 降级与熔断：停掉一个 Server，任务不 500、能降级 | 停 8001 后"现在几点"走降级或明确报错，trace `status=DEGRADED` | ✅ 完成（2026-11-02：`DEGRADED`+`TOOL_ERROR`；3 次后 `state=OPEN`；再调 `CIRCUIT_OPEN` 且 `latencyMs=0`；`mvn test` 24/24） |
| M6 | 实验脚本：批量跑任务集，输出 CSV/JSON 指标 | 一键跑出成功率/延迟表 | ⬜ 未开始 |

## 八、实验设计（论文用）

**对照组**

| 组 | 编排方式 | 说明 |
|---|---|---|
| A | 哑转发（阶段 0） | 不规划，agent-worker 单轮回答 |
| B-rule | 规则编排 | 关键词 → 工具映射，无 LLM 规划 |
| B-llm-3b | LLM 规划（3b） | 单层，规划执行同模型 |
| B-llm-7b | LLM 规划（7b） | 单层，规划执行同模型 |
| B-dual | 双层（7b 规划 + 3b 执行） | 设计默认形态；2026-10-28 起执行器默认 7b，跑该组需显式 `LLM_MODEL=qwen2.5:3b` |

**任务集**：30 条，按难度分层——单步 / 两步 / 三步 / 越界（应拒绝）/ 缺参（应追问）。
**指标**：任务成功率（人工判定，双人标注不一致时第三人裁）、端到端 P50/P95 延迟、平均步数与工具调用次数、工具选择准确率（人工标注金标准）、失败模式分布（错选/漏调/多调/超时）。

**可复现**：锁定 `temperature=0.3`、记录 trace JSONL、任务集与标注表随代码入库。

## 九、明确不做（列为扩展）

1. **`sampling`**：Server 反向借 Host 模型。协议使用最完整，但需同时改 Java 的 client 能力声明与 Python 的 sampling 回调，且要处理服务端反向等待的并发问题，风险高，留作第 8 章扩展。

> 官方文档：[添加官方文档内容]
2. **注册中心**：本阶段仍是 `application.yml` 静态连接；动态注册发现放 U5。
3. **Agent 间协商**：当前所有通信经 Host 中转（星型）。点对点互调或共享黑板属于"真多智能体"，放 U5 之后决策。

## 十、相对阶段 0 的文件变更清单

| 文件 | 变更 |
|---|---|
| `demo/java-mcp-client/pom.xml` | 加 `spring-ai-starter-model-ollama` |
| `demo/java-mcp-client/.../application.yml` | 加 `spring.ai.ollama.*`、`spring.ai.mcp.client.toolcallback.enabled=true` |
| 新增 `config/McpToolPrefixConfig.java` | `McpToolNamePrefixGenerator` bean |
| 新增 `catalog/ToolCatalogService.java` | 目录构建、缓存、失效 |
| 新增 `orchestrator/TaskOrchestrator.java` | 状态机、并发、超时、降级 |
| 新增 `orchestrator/TraceRecorder.java` | JSONL trace 落盘 |
| 新增 `web/OrchestratorController.java` | `/api/catalog`、`/api/task` |
| `demo/py-agent/agent.py` | 加 `summarize` / `translate` 能力工具（含 `title` 与"不做什么"描述）；`run_task` description 改为"通用兜底 + 写作类直答"；执行器默认模型 7b（✅ 2026-10-28） |
| `demo/py-mcp-server/server.py`、`demo/py-agent/agent.py`、`McpDemoApplication.java` | 修正"stdio 传输"的过期注释（✅ 2026-10-28） |

> 实现备注（2026-10-28）：实际 Java 包名为 `agent/`（不是上表的 `orchestrator/`）；工具目录暂由 `CatalogController` 即时构建，`ToolCatalogService` 尚未单独抽出，缓存/`list_changed` 失效留到 R1 一起做。
>
> 实现差异（2026-10-28）：任务总超时默认 300s（`mcp.planner.task-timeout-seconds`，设计稿 4.5 为 120s）；`maxSteps` 默认 10（设计稿为 5）；多个 `tool_calls` 目前经 `ToolCallingManager` 串行执行，未使用 `McpAsyncClient` 并行；单次 LLM 60s 超时已落地。以上参数在 M6 评测前统一定标。
>
> 实现备注（2026-10-28，M4）：执行器默认模型改为 `qwen2.5:7b`；`max_words` 只是提示性约束——实测 3b 给 30 字上限时输出 121 字、7b 输出 77 字，未做截断/重试（按"基础版本不加规则兜底"约定）；`run_task` 在 5 条验收样本中已不再被选中，是否从目录摘除见第十一节待决问题 4。

## 十一、待决问题

1. 工具表分组（先选域再选工具）是否在第一版做？——建议暂缓，先观察 7b 模型在 5 个工具内的选择准确率。
2. Agent 崩溃后熔断的半开探测周期（30s？60s？）——等 M5 实测再定。
3. 是否把 `/api/task` 改成 SSE 流式返回进度——等 trace 数据说明单任务耗时分布后再定。
4. `run_task` 是否从工具目录摘除、只留作降级兜底？——M4 的 5 条样本里它已不再被选中；摘除能进一步降低误选，但"多智能体协作"叙事少一个通用执行器入口，建议作为 M6 的一个对照组（有/无 fallback 工具）再定。
5. MCP 会话重连（`session terminated` 后自动重建 `McpSyncClient`）——M5 已确认这是"服务重启后熔断无法自动恢复"的根因，留到 R1 与注册中心一起做。
4. `run_task` 是否从工具目录摘除、只留作降级兜底？——M4 的 5 条样本里它已不再被选中；摘除能进一步降低误选，但"多智能体协作"叙事少一个通用执行器入口，建议作为 M6 的一个对照组（有/无 fallback 工具）再定。
