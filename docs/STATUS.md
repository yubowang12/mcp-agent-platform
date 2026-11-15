# 项目状态与进度日志

> **这是"换会话 / 换 agent"的入口文档。** 30 秒读完：项目是什么、现在到哪、下一步做什么、怎么跑、材料在哪。
>
> 维护约定：每天结束时在「日志」顶部加一条（模板在文末），状态表有变化就一起改。
> 最近更新：2026-11-15

---

## 1. 当前状态

| 项 | 状态 |
|---|---|
| 项目定位 | **求职作品集**（Agent 开发 / 后端实习），毕业设计只是可能用途之一 |
| 阶段 | **阶段一（M1~M6）已完成**；**记忆层 v1 已实现、已拆包、功能复核完成**（A1~A5/A7/A8 通过；**A6 已于 2026-11-10 按方案 b 修复**）；**RAG v1 已实施，R1~R8 待评审**；**2026-11-14 完成可靠性评审；v1.1 收口进行中（W1/W2 已完成，§3.1）** |
| 架构 | Java（Spring AI）规划型 Host `:8090` + Python MCP 服务（工具 `:8001`、Agent `:8002`、RAG `:8003`）+ DashScope `qwen-plus` |
| 测试 | **82/82**（79 单测 + 3 集成），跑在 `qwen-plus` 上；W1/W2 只跑了相关子集，见 §3.1 |
| 评测 | 30 条任务集：严格 25/30（83.3%）、宽松 27/30（90%）。⚠️ 这是 **Ollama 基线**，换模型后未重跑 |
| 文档 | 13 份（见第 6 节） |
| 未提交 | 无（最新提交 `70f7327`，v1.1 W1；W2 待提交） |

## 2. 现在在做什么

- **2026-11-14 完成 v1.1 可靠性评审**（外部评审 + 本会话逐条复核）：采纳 8 条、修正 3 条、降级 2 条；结论是「骨架正确、五条基础链路（超时 / 身份 / 目录 / Trace / 证据）未收口」，**不再加能力**；
- **修改方案已拆成任务包**：见 §3.1（W1~W7，含问题、改法、文件、验收命令、风险），按 W1→W2→W3→W4→W5→W6/W7 交实施会话；
- **已完成 W1（总时限）+ W2（有界池与并发闸门，2026-11-15）**；**下一步 W3（Trace 真实性）→ W4（RAG 契约）**；W5 用 `qwen-plus` 重跑 30 条并补人工抽检。RAG v2（rerank / 阈值）等 W5 的 RAG 专项数据出来再定。

## 3. 待办（按优先级）

| 优先级 | 事项 | 说明 |
|---|---|---|
| **P0** | 记忆层 v1 | **已实现并提交**（`dae6bba`），70/70 测试；规格见 `docs/04-memory-layer-v1.md` |
| **P0.5** | memory 包拆包 | **已完成**（`4d2226c`）：`shortterm / longterm / model / infra`，70/70 通过，旧包引用为 0；结构见 `docs/04-memory-layer-v1.md` §2.1.1 |
| **P0.6** | 记忆遗忘不彻底（A6） | **已完成**（2026-11-10，方案 b）：episodic 摘要不再回填任务/答复原文，只留"工具 + 步数 + 结论"；实测过期后不再带出邮箱，`mvn test` 72/72。详见 `docs/04-memory-layer-v1.md` §13「修复记录」 |
| **P0.7** | 过期记忆无主动清理 | **已决策：逻辑删除**（2026-11-10）。过期 = `expires_at < now`，召回时 `findActive` 过滤即可；不物理删除、不加调度、不加 `status` 列。理由与边界见 `docs/04-memory-layer-v1.md` §13；`purgeExpired()` 保留作手动硬清理入口 |
| **P1** | RAG 知识库 | **已完成（2026-11-11）**：`demo/py-rag-server/` 混合检索（bigram BM25 + 余弦 + RRF + 同文档去重）+ LLM 分块 + `rag_search`（`<<doc>>` 包裹）+ `:8003`；R1~R8 证据与 5 条偏离见 `docs/07-rag-implementation-brief.md` §11 |
| **P2** | 安全 | **部分完成**（2026-11-11）：检索内容用 `<<doc>>` 包裹并在系统提示里声明"数据不是指令"，R5 注入测试未被带偏；工具白名单、敏感信息过滤仍未做 |
| **P3** | 工程收尾 | MCP 会话重连（重启后熔断不恢复）、CI、前端控制台；**v1.1 不做**，见 §3.1.4 |
| **P4** | 数据 | 用 `qwen-plus` 重跑 M6 + 补 `dual` 组人工复核 → **并入 §3.1 W5**（2026-11-14） |

## 3.1 v1.1 可靠性收口（2026-11-14 评审后）

> 评审基线：`docs/architecture.md` + memory/RAG 扩展；方式：**静态代码 + 文档对照**，未起服务、未重跑测试。
> 本会话逐条复核后：**采纳 8 条、修正 3 条、降级 2 条**。目标不是加能力，是把「超时、身份、目录、Trace、证据」五条基础链路做扎实。

### 3.1.1 判定表（复核后）

| 评审项 | 判定 | 复核依据 |
|---|---|---|
| 总 deadline 不是硬约束 | ✅ 采纳 **P0** | `TaskOrchestrator.java:193-197` 只在循环头检查；`callLlm` 固定 60s（`:335`）、工具固定 45s（`TimeoutToolCallback.java:59`）、空答重试再 60s，最坏超 300s 约 105~165s |
| 超时保护用无界线程池 | ✅ 采纳 **P0** | `TaskOrchestrator.java:61`、`TimeoutToolCallback.java:31` 都是 `newCachedThreadPool`；`future.cancel(true)` 不保证中断阻塞中的 MCP HTTP 调用 → 慢服务 + 并发会堆线程/连接 |
| Trace 把未知调用记成成功 | ✅ 采纳 P1 | `TaskOrchestrator.java:350-359` 找不到记录时 `orElseGet(... ok=true, 0ms)`；`terminate` 是本地工具、未包超时/熔断，需与"未知工具"区分 |
| `tokenUsage/retryCount` 恒为空 | ✅ 采纳（非 bug） | `TaskTrace.java` 的 javadoc 明说 M2 起恒为 null；作为实验数据必须补，否则成本/重试无从分析 |
| RAG 索引不随 ingest 热更新 | ✅ 采纳 P1 | `server.py:57-65` 只在 `INDEX is None` 时补加载；重构库后仍查旧矩阵，需重启 |
| 防注入只是提示词缓解 | ✅ 采纳 P1 | `server.py:51-54` 的 `_wrap` 未转义正文里的 `<<doc` / `<</doc>>`；`docs/06` 要求的输出侧引用校验未实现 |
| 评测只证明链路能跑 | ✅ 采纳 P1 | `run_eval.py:117-138` 严格分 = `status=OK` + 工具序列 + 答案非空，不判内容；`:365` 串行；30 条结果仍是 Ollama 基线（`RESULTS.md:3`） |
| Catalog 生命周期未实现 | ⚠️ 修正 P2 | 框架层 `SyncMcpToolCallbackProvider` 已有 `cachedToolCallbacks` 字段并实现 `ApplicationListener<McpToolsChangedEvent>`（`invalidateCache()`），**缓存与失效是现成的**；问题在 `CatalogController.java:31` 自己 `new` 了一个 provider，不是 bean，收不到事件。改为注入自动装配的 `mcpToolCallbacks` 即可，不必自写缓存 |
| 身份用 `serverInfo.name()` 违反约束 | ⚠️ 修正 P2 | `javap` 确认 `McpConnectionInfo` 只有 `clientCapabilities/clientInfo/initializeResult`，**拿不到 connections key**；Spring AI 自带 `DefaultMcpToolNamePrefixGenerator` 也只用 clientInfo + serverInfo。换 key 需自建 client，或按 `NamedClientMcpTransport` 顺序配对（脆弱）。v1.1 先做**唯一性校验 + 失败即停**，完整映射留 R1 spike |
| 8003 不在线仍诱导调用 | ⚠️ 降级 P3 | 启动 fail-fast 保证应用起来时 8003 在线；运行中掉线是"工具在、调用失败"，已按 `TOOL_ERROR` 降级。硬编码提示词仍是坏味道，v2 从 catalog 动态生成 |
| 规划 LLM 无规则兜底 | ✅ 采纳 P2 | `architecture.md:193` 写了"回退到规则编排"，代码未实现；v1.1 可选补，或把文档标成 v2 |
| 状态机 / 并行与文档不一致 | ⚠️ 降级（不做） | 实际是隐式 ReAct；`architecture.md:313` 已注明"缓存/失效留到 R1"。v1.1 只把文档改成描述当前实现，不为了"像状态机"重写循环 |

### 3.1.2 任务包（按实施顺序）

**W1 任务总时限硬约束（P0）— ✅ 已完成（2026-11-14）**
- 问题：deadline 只在 while 条件判断，单轮 LLM + 工具 + 空答重试都可能越过 300s。
- 实现（与计划的差异：工具表在构造时一次性建好，无法按请求注入 remaining，改用 ThreadLocal）：
  1. 新增 `TaskDeadline`（ThreadLocal）：`run()` 入口 `set(deadline)`、`finally clear()`；未设置时 `remainingMillis()` 返回 `Long.MAX_VALUE`（各层退回固定超时）。
  2. `callLlm`：`future.get(min(llmTimeout, remaining), MILLISECONDS)`；剩余先耗尽 → `TaskTimeoutException`，否则才是 `LlmTimeoutException`。
  3. `TimeoutToolCallback.call()`：`budget = min(toolTimeout, remaining)`；`budget ≤ 0` 时不提交工具、直接返回"任务总时限已用尽"并记 `TOOL_TIMEOUT`。
  4. 新增 errorCode `TASK_TIMEOUT`（总时限，`status=TIMEOUT`）；deadline 与 `maxSteps` 同时命中时 deadline 优先。
- 验收（4 个新用例全过，与既有用例合计 16/16）：
  - `TaskDeadlineTest`：① 无 deadline 不设限；② 剩余 400ms 时 3s 工具在 <2s 内被截断；③ 预算已耗尽时不执行工具；④ 预算充足时仍用配置超时。
  - 命令：`mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml -Dtest="TaskDeadlineTest,TaskDegradationTest,TimeoutToolCallbackTest,CircuitBreakingToolCallbackTest" test`
- 风险（已处理）：`TaskOrchestratorTest` 签名未变；新用例纯单测，不依赖网络/模型。

**W2 有界线程池 + 并发闸门（P0）— ✅ 已完成（2026-11-15）**
- 问题：两个池无界，`cancel(true)` 拦不住阻塞的 MCP 调用，慢服务叠加并发会堆线程/连接。
- 改法：
  1. 两个池改 `ThreadPoolExecutor(core=max=N, 有界队列, AbortPolicy)`，配置：`mcp.planner.llm-pool-size=8`、`llm-queue=32`、`tool-pool-size=16`、`tool-queue=64`；`@PreDestroy` 优雅关闭。
  2. 拒绝策略：LLM → 抛 `LlmOverloadedException` → `status=ERROR, errorCode=OVERLOAD`；工具 → 返回"系统繁忙"文本 + `TOOL_ERROR`（或新增 `OVERLOAD`）。
  3. 入口闸门：`TaskController` 用 `Semaphore(max-concurrent-tasks=8)`，`tryAcquire` 失败立即 503 + `Retry-After`（可配 `mcp.planner.max-concurrent-tasks`）。
  4. Python 侧：`agent.py` / `ingest.py` 的 `OpenAI(...)` 显式 `timeout=60, max_retries=1`（默认 600s 太长）。
- **实施记录（2026-11-15）**：
  - `PlannerProperties` 加 `llmPoolSize / llmQueue / toolPoolSize / toolQueue / maxConcurrentTasks`，默认 8/32/16/64/8；`application.yml` 同步。
  - `TaskOrchestrator`：静态 `cachedThreadPool` 换成实例级 `boundedPool(...)`（core=max、`ArrayBlockingQueue`、`AbortPolicy`、守护线程、`allowCoreThreadTimeOut`），`@PreDestroy shutdownPools()`；工具池注入 `TimeoutToolCallback`，生产与测试共用同一有界池。
  - `TimeoutToolCallback` 新增 3 参构造（外部注入池）；2 参构造保留，用静态有界默认池（16/64），单测与旧调用方不受影响。池/队列满 → 立即返回「系统繁忙（工具调用并发已满），请稍后重试。」并记 `TOOL_ERROR`。
  - `TaskConcurrencyLimiter`（新，`@Component`）：公平 `Semaphore(capacity)`，`tryEnter/exit/inFlight/capacity`；`TaskController` 校验参数后 `tryEnter`，失败抛 `TaskBusyException`（503 + `Retry-After: 1`），`finally exit()`。
  - Python：`demo/py-agent/agent.py:69` 与 `demo/py-rag-server/config.py:86` 的 `OpenAI(...)` 都加 `timeout=60, max_retries=1`。
- 验收：
  - 单测：池满时第 N+1 次调用 ≤100ms 返回"繁忙"，不阻塞；
  - 并发脚本：20 个并发 `/api/task`，8 个 200、其余 503，线程数不随请求线性增长；
  - 现有超时/熔断单测全过。
- **已验**：`TaskConcurrencyLimiterTest`（3）+ `TimeoutToolCallbackTest` 新增 `saturatedPoolReturnsBusyImmediately` + 既有 16 个 = 20/20；再加 `TaskControllerConcurrencyTest`（2，standalone MockMvc：满闸门 → 503 + `Retry-After: 1`，成功请求归还许可）= **22/22** 通过（`mvn ... -Dtest="TaskConcurrencyLimiterTest,TimeoutToolCallbackTest,TaskDeadlineTest,CircuitBreakingToolCallbackTest,TaskDegradationTest" test`），未起服务。
- **实测（2026-11-15，8001/8002/8003/8090 全起）**：
  - 20 并发 `/api/task` → **8×200 + 12×503**（全部带 `Retry-After: 1`）；200 p50 1441ms / p95 2105ms，503 p50 ~380ms（拒绝不排队）。
  - 50 并发 → **8×200 + 42×503**；503 p50 193ms。
  - trace：两轮共 16 条，全部 `status=OK`、无 `OVERLOAD`；503 请求不进编排、不落 trace。
  - 线程数：空闲 75，20 并发峰值 142，50 并发峰值 113 —— 不随并发线性增长。
- **实测抓到的启动 bug（已修）**：有界池 `keepAlive=0` + `allowCoreThreadTimeOut(true)` 抛 `IllegalArgumentException: Core threads must have nonzero keep alive times`，应用起不来；改为 60s keep-alive。`NoToolsAvailableTest` 复跑 1/1，证明该类构造路径已被测试覆盖。`NoToolsAvailableTest` 的 `PlannerProperties` 已补 9 参。
- 风险：503 会让"无脑并发"的脚本失败（串行 eval 不受影响）；默认并发别压到 1。

**W3 Trace 真实性（P1）**
- 问题：未知调用默认 `ok=true, 0ms`；`tokenUsage/retryCount` 恒 null。
- 改法：
  1. `recordOf` 找不到时返回 `null`；调用处对 `terminate` 显式记 `ok=true`，其余 null → `ok=false, errorCode=UNKNOWN_TOOL`。
  2. `tokenUsage`：从 `ChatResponse.getMetadata().getUsage()` 取 prompt/completion/total 并累加；拿不到写 null，不要写 0。
  3. `retryCount`：空答重试次数（0/1）。
- 验收：新增 `TraceTruthfulnessTest`——① 调用目录外的工具名 → step `ok=false, errorCode=UNKNOWN_TOOL`；② `terminate` → `ok=true, 0ms`；③ 一次空答重试 → `retryCount=1`；④ 正常任务 → `tokenUsage.totalTokens > 0`。
- 风险：`TraceStep`/`TaskTrace` 是 record，加字段要同步 trace 消费方（`run_eval.py` 只读 `steps[].toolName/status`，兼容）。

**W4 RAG 契约与生命周期（P1）**
- 4a 索引热更新：每次 `rag_search` 先 `os.stat(config.DB_PATH).st_mtime_ns`，与加载时不同则重载并**原子替换**；重载失败保留旧索引 + warn，返回结果里加 `indexVersion`。
- 4b 边界转义：`_wrap` 前把正文 `<<doc` → `‹‹doc`、`<</doc>>` → `‹‹/doc››`（或插入零宽字符），保证注入内容不能提前闭合边界；R5 增加一条含 `<</doc>>` 的语料。
- 4c 引用校验（Host 侧）：收尾时正则 `\[([a-z0-9-]+)\s*§` 提取 docId，与本次 `ragserver_rag_search` 返回的 docId 集合比对；不在集合 → trace `citation_invalid=true`（不删答案，可选把 `status` 降为 `DEGRADED`）。
- 4d 错误契约：保持 `code: message` 文本（MCP isError 只能文本），在 `docs/06` 补 code 表。
- 验收：① 新增文档后不重启即可命中；② 注入含 `<</doc>>` 的段落 → 边界仍完整、模型不执行；③ 编造引用 → trace 有 `citation_invalid`；④ `retrieval.py --selftest` 仍 4/4。

**W5 证据重建（P1）**
- 5a 全量重跑：`python demo/eval/run_eval.py --group dual`（30 条，qwen-plus）+ `--group direct` 对照；`RESULTS.md` 新增 2026-11-14 表（模型、时间、严格/宽松、P50/P95、失败模式），Ollama 表保留并标"历史基线"。
- 5b 人工抽检：从 30 条抽 10 条（dual 6 + direct 4），按 对 / 部分 / 错 打分并写理由，进 `RESULTS.md` 新表。
- 5c RAG 专项：`demo/eval/rag_tasks.json` 固化 10 题（5 gold + 3 域外 + 1 注入 + 1 引用），记录 top5 命中与引用合法性。
- 5d 熔断/超时复现：把"停 8002 → 3 次 DEGRADED → OPEN → CIRCUIT_OPEN 0ms"固化成 `demo/eval/resilience_check.ps1` 或文档步骤。
- 验收：RESULTS.md 有三张新表；README/STATUS 的 `72/72` 与评测数字同步；每条结论能指到 `results/<runId>/`。

**W6 Catalog 注入 + 文档对齐（P2）**
- `CatalogController` 改为注入自动装配的 `SyncMcpToolCallbackProvider`（bean `mcpToolCallbacks`），删除自建 provider；`TaskOrchestrator` 同样改注入 provider 的 callbacks，再包超时/熔断（保留 prefix→server 映射）。
- 效果：`McpToolsChangedEvent` → `invalidateCache()` 自动生效，`/api/catalog` 与模型工具表都能刷新。
- 文档：`architecture.md` §4.1 标"目标设计（v2）"、`:313` 实现备注更新；§4.7 规则兜底标"未实现（W7）"；§4.3 的"健康度过滤"与实现差异补一句。
- 验收：`/api/catalog` 与改前一致；手动触发 `McpToolsChangedEvent` 后目录重新 list（日志可证）；相关单测通过。

**W7 规划 LLM 规则兜底（P2，可选）**
- `callLlm` 抛 `LlmTimeoutException` / 其它异常时，按关键词路由执行一次（含"时间"→`demotools_get_time`，含"加/减/算"→对应工具），结果作为答案并标 `status=DEGRADED, errorCode=PLANNER_FALLBACK`；再不命中 → `ERROR`。
- 验收：单测 mock `ChatClient` 抛异常 → 走 `get_time` 且 trace 有 `PLANNER_FALLBACK`；正常路径不变。

### 3.1.3 实施纪律

- 一次只做一个包；**W1 与 W2 同区**（`TaskOrchestrator.java` / `TimeoutToolCallback.java`），必须串行。
- 先写/改测试再改实现；单测用 `-Dtest=`，全量测试需 8001/8002/8003 在线。
- 不改 `AGENTS.md`、`STATUS.md`、`application-local.yml`、`.gitignore`；不 commit；改完按五项回报（结论 / 证据 / 改动 file:line / 未决 / 用量）。
- 行为变化（503、新 errorCode、新 trace 字段）先在本节补一行决策，再动代码。

### 3.1.4 不做（v1.1 范围外）

- 并行工具调用、显式状态机（`PLANNING/EXECUTING/OBSERVE`）——文档改为描述当前隐式 ReAct；
- 向量数据库、RAG rerank / 分数阈值——等 W5 的 RAG 专项数据再定（v2）；
- MCP 会话重连（R1）、前端控制台——仍在 P3。

## 4. 关键约束（换人做也别踩）

1. **密钥**：只放 `demo/java-mcp-client/src/main/resources/application-local.yml`（gitignored）。**不要**写进 `application.yml`。
2. **base-url**：Java 侧 `spring.ai.openai.base-url` 到 `.../compatible-mode`（**不带 `/v1`**，Spring AI 自己拼）；Python 侧 `LLM_BASE_URL` 要**带 `/v1`**。
3. **测试前提**：3 个集成用例需要 8001/8002 在线（RAG 落地后加 8003）；测试类已 `@ActiveProfiles("local")`。跑 `mvn test` 前没起服务会启动失败（fail-fast，不是 bug）。
4. **评测口径**：严格 = `status=OK` 且工具序列完全匹配；比较时**剔除 `terminate`**；改口径用 `--rescore` 复算，不重跑模型。
5. **启动期 fail-fast**：8001/8002 任一不在，Java 起不来。这是已知限制，重连排在 P3。
6. **决策方式**：方案由用户定；提工程选项时先解释术语、给 2~3 个选项和代价（见 `development-protocol.md` 约定 9/10）。

## 5. 怎么跑

```powershell
# 一键启动（读 application-local.yml 的 Key，激活 local profile）
.\demo\start-dev.ps1

# 冒烟：一条任务
$body = '{"task":"现在几点？","maxSteps":10}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod 'http://localhost:8090/api/task' -Method Post -Body $bytes -ContentType 'application/json; charset=utf-8'

# 单测（不需要服务、不调模型）
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml -Dtest="CircuitBreakerTest,CircuitBreakingToolCallbackTest,TimeoutToolCallbackTest,TaskDegradationTest,NoToolsAvailableTest" test

# 全量（需 8001/8002 + Key）
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml test

# 评测
python demo/eval/run_eval.py --group dual
python demo/eval/run_eval.py --rescore demo/eval/results/<runId>

# RAG（实施后；详情见 docs/07）
& demo\.venv\Scripts\python.exe demo\py-rag-server\ingest.py
& demo\.venv\Scripts\python.exe demo\py-rag-server\server.py
```

## 6. 文档地图

| 想干什么 | 看哪个 |
|---|---|
| **接手项目** | **本文件（STATUS.md）** |
| 简历怎么写、面试怎么讲 | `docs/portfolio.md` |
| 看懂系统怎么跑（图） | `docs/01-architecture-flow.md` |
| 背面试题 | `docs/02-interview-and-resume.md` |
| 逐段读代码 | `docs/03-code-walkthrough.md` |
| **实施记忆层 v1** | `docs/04-memory-layer-v1.md`（决策、表结构、验收） |
| **做 RAG 知识库（实施）** | `docs/07-rag-implementation-brief.md`（执行清单、命令、坑、完成定义） |
| RAG 规格与决策 | `docs/06-rag-v1.md`（主题卡、已定决策、验收 R1~R8、方案要点） |
| **拆包目标结构** | `docs/04-memory-layer-v1.md` §2.1.1（执行步骤见对话记录） |
| 架构决策与取舍 | `docs/architecture.md` |
| 需求/设计全貌 | `docs/project-design-document.md` |
| 开发流程、约定、M4/M5/M6 验收记录 | `docs/development-protocol.md` |
| 学习单元与验收 | `docs/learning-plan.md` |
| 评测数据 | `demo/eval/RESULTS.md` |
| 阶段 0 技术验证 | `docs/tech-validation-report.md` |

## 7. 给接手者（人 / agent）的提示

- 先读本文件 → 再读 `development-protocol.md` 的「M5/M6 决策记录」→ 其余按需；
- **不要重问已完成的事**：M1~M6 已闭环，细节都能指到代码或文档；
- 改动前先看第 4 节的约束（密钥、base-url、测试前提、评测口径）；
- 新增决策时，同步往 `development-protocol.md` 追加一行，别只留在对话里。

---

## 8. 日志（倒序）

### 2026-11-15 · v1.1 W2 完成：有界线程池 + 入口并发闸门

- **背景**：W1 只让调用方"不再等"，被取消的调用仍占着线程；慢服务叠加并发会无界堆线程/连接。
- **改了什么**：
  - `TaskOrchestrator` 的两个静态 `cachedThreadPool` 换成实例级有界池（`core=max=N` + `ArrayBlockingQueue` + `AbortPolicy` + 守护线程 + `allowCoreThreadTimeOut`），`@PreDestroy` 关闭；工具池注入给所有 `TimeoutToolCallback`。
  - LLM 池/队列满 → `LlmOverloadedException` → `status=ERROR, errorCode=OVERLOAD`（不排队不重试）；工具池满 → 立即返回「系统繁忙」+ `TOOL_ERROR`。
  - 新增 `TaskConcurrencyLimiter`（公平 `Semaphore`）与 `TaskBusyException`：`/api/task` 超并发上限直接 **503 + `Retry-After: 1`**，`finally` 释放。
  - 配置：`mcp.planner.{llm-pool-size:8, llm-queue:32, tool-pool-size:16, tool-queue:64, max-concurrent-tasks:8}`。
  - Python：`demo/py-agent/agent.py:69`、`demo/py-rag-server/config.py:86` 的 `OpenAI(...)` 显式 `timeout=60, max_retries=1`。
- **验证**：`TaskConcurrencyLimiterTest`（3）+ `TimeoutToolCallbackTest` 新增池满用例 + 既有 16 个 = 20/20；另加 `TaskControllerConcurrencyTest`（2）覆盖 503/`Retry-After` 契约，合计 **22/22**。未起服务、未跑集成。
- **注意**：503 是"拒绝"不是"失败"，压测脚本要按 503 统计；串行 eval 不受影响。20 并发下 200/503 的分布待服务起来后补测。


### 2026-11-14 · v1.1 W1 完成：任务总时限变成硬约束

- **背景**：v1.0 已冻结并推到 GitHub（`master` + tag `v1.0`），v1.1 在 `v1.1-reliability` 分支开工。
- **改了什么**：新增 `TaskDeadline`（ThreadLocal，`run()` 设置、`finally` 清理）；`callLlm` 取 `min(llmTimeout, remaining)`；`TimeoutToolCallback` 取 `min(toolTimeout, remaining)`，预算耗尽时不提交调用；新增 `TASK_TIMEOUT`（`status=TIMEOUT`），与单工具 `TOOL_TIMEOUT` 区分；LLM 超时按"剩余是否耗尽"分流到 `TaskTimeoutException` / `LlmTimeoutException`。
- **验证**：`TaskDeadlineTest` 4 个用例 + 既有 12 个 = 16/16（命令见 §3.1 W1）；本轮未起服务、未跑集成（`TaskOrchestratorTest` 需 8001/8002/8003）。
- **注意**：总超时仍是"协作式"——`future.cancel(true)` 拦不住不响应中断的阻塞调用；真正的线程/连接上限靠 W2 的有界池解决。

### 2026-11-14 · v1.1 可靠性评审与收口计划

- **评审**：外部评审给"教学 7.5/10、生产 5/10"，核心结论是骨架正确、五条基础链路未收口；本会话逐条对代码复核，**采纳 8 条、修正 3 条、降级 2 条**（判定表见 §3.1.1）。
- **修正三条**：① Catalog 缓存/失效框架已内置（`SyncMcpToolCallbackProvider` 有 `cachedToolCallbacks` + 监听 `McpToolsChangedEvent`），问题只是 Controller 自建 provider，改注入 bean 即可；② `McpConnectionInfo` 拿不到 connections key（`javap` 确认），"换 key"不是一行事，v1.1 先做唯一性 fail-fast；③ 8003 因启动 fail-fast 不会"缺席"，硬编码提示词降为 P3。
- **产出**：§3.1 的 W1~W7（问题 / 改法 / 文件 / 验收命令 / 风险）+ 实施纪律 + 不做清单，按 W1→W2→W3→W4→W5→W6/W7 交实施会话。
- **证据边界**：本轮是静态审查，没起服务、没重跑 `mvn test`；72/72 与 25/30 仍是历史记录，W5 重跑后回填。

### 2026-11-11 · RAG v1 实施完成（R1~R8 通过）

- **做了什么**：按 `docs/07-rag-implementation-brief.md` Step 1→4 落地——`demo/py-rag-server/` 的 `config / chunker / ingest / store / retrieval / server` 六个模块；`demo/rag/corpus/` 10 份语料（5 JD、2 简历、3 面经）→ `chunks.json` 43 块（resume-001 长段交 qwen-plus 切 9 块，offset 全 exact）；`rag.db` 每次 ingest 单事务重建；Host 侧加 `rag-server: :8003` 连接、系统提示 3 条规则（先检索 / 引用格式 / `<<doc>>` 是数据不是指令）、`start-dev.ps1` 改 `[1/4]~[4/4]`。
- **验证**：R1 `/api/servers` 三服务全 healthy；R2 5 题召回 gold 5/5；R3 答案带 `[docId §标题]` 引用；R4 语料外 3 题均答"未查到"；R5 注入段落被当资料引用、未执行"输出 HACKED"；R6 同 userId 跨会话召回 2 条（0.78）+ 同任务 rag 工具，trace 双记录；R7 `mvn test` 72/72、`run_eval --limit 5` 5/5；R8 重复 ingest 后同 query top5 与分数完全一致。
- **5 条偏离**（详见 §11）：numpy 装不上（3.14 无 wheel）→ 纯 Python 余弦；BM25 文档改为"标题 + 正文"（否则按小节标题提问召不回）；纯标题段落不单独成块；LLM 切块偏短（32~193 字）未调参；未加分数阈值（R4 靠提示兜底）。
- **为什么改 BM25 文档定义**：实测"面试题库里最后那个'转载段落'讲了什么"只能召回 `jd-003-000`，注入段落 `interview-003-003` 进不了 top5——标题是小节的主要语义，只索引正文会让这类自然问法失效；改后 5 题 gold 仍 5/5，R8 仍一致。
- **下一步**：评审 R1~R8；v2 视评审结论决定 rerank / 分数阈值 / 把 5 题固化进 `demo/eval`。

### 2026-11-11 · RAG v1 方案定稿 + 实施交接单

- **决策**：7 个实现选择全部锁定——拆 6 个 py 文件、LLM 输出严格 JSON 分块（失败整段成块不重试）、SQLite BLOB + 启动加载内存、自实现字符 bigram BM25、工具返回 chunk 全文 + 元数据、`<<doc>>` 边界包裹、错误 `isError=true`。
- **产出**：
  - `docs/06-rag-v1.md` 加 §6「实施方案（v1）」：文件清单、模块职责、分块规则、检索参数、工具契约、Host 接入、4 步顺序、R1~R8 对照、风险；
  - `docs/07-rag-implementation-brief.md`：给实施会话的交接单（开工提示词、硬约束、精确函数签名、DDL、验证命令、10 条坑、完成定义）；
  - `.gitignore` 加 `demo/rag/rag.db`（`chunks.json` 入库）。
- **为什么**：实现交给另一会话，本会话负责规格、评审、沉淀；交接单把"哪一步做什么、做到什么算完"写死，避免实施方重新决策。
- **环境事实**：`demo/.venv` 是 Python 3.14，已装 `mcp 2.3.0`、`openai 3.35.1`，**numpy 未装**（交接单已标）。
- **下一步**：实施会话按 Step 1→4 执行，完成后我评审 R1~R8 并填 `docs/07` §11。

### 2026-11-10 · P0.7 决策：逻辑删除，不做物理清理

- **结论**：过期 = `expires_at < now`，召回时 `findActive` 过滤即可，**不删数据、不加 `status` 列、不引入定时任务**。
- **理由**：功能上等价（过期数据不进 `recalled`，也就不进 prompt）；只有 episodic 有 TTL（90 天），单用户每天至多 1 条，增长极慢；保留数据便于回溯排查；少一个调度线程和一套并发/失败处理。
- **边界**：`list()` 仍能看到过期项，将来对外暴露记忆管理接口时返回**派生 `expired` 字段**即可；如遇隐私合规诉求，走显式 `forget`（硬删）或手动 `purgeExpired`。
- **保留**：`purgeExpired()` 作为手动/管理用硬清理入口（已有单测），不接调度。
- **代码**：无改动（现有 `findActive` 过滤即为最终行为）。

### 2026-11-10 · P0.6 记忆遗忘不彻底（方案 b）

- **做了什么**：`MemoryExtractor.extractEpisodic()` 不再把**任务原文**与**答复原文**写进摘要，episodic 固定为
  `会话摘要｜工具：<工具/无>｜步骤：<N>｜结论：完成`——只记"做了什么 + 结论"，事实归 semantic 管。
  连答复也不回填，是因为复核复现里邮箱在任务和答复里各出现一次，只去掉任务仍会漏。
- **为什么选 b**（规格 §13 的四个选项）：a 会失去会话摘要、c 的级联过期容易误删别的会话、d 治标；
  b 从源头消除"同一事实存两份"，代价是摘要信息量下降（可接受）。
- **验证**（独立库 + 独立 trace，qwen-plus）：
  - 写入"记住：我的邮箱是 `fix-verify@example.com`" → 写入 semantic（原句）+ episodic（摘要，**不含邮箱**）；
  - 把 semantic `expires_at` 改到 2000 年 → 新会话问"我的邮箱是什么？"：
    召回只剩 1 条 episodic、答案"我无法访问或查询你的邮箱地址…"，**不再出现邮箱**；
  - `episodic` 表里 `text like '%fix-verify@example.com%'` → **0 行**。
  - `mvn test` → **72/72**（70 + `MemoryForgettingTest` + `MemoryExtractorTest` 新用例）。
- **新增回归测试**：`MemoryForgettingTest`（把复核的三步固化成测试）、`MemoryExtractorTest.episodicDoesNotEchoTaskOrAnswer`。
- **文档**：`04` §13 追加「修复记录」；`development-protocol.md` 决策记录加第 9 行。
- **下一步**：P0.7（过期数据主动清理，`@Scheduled` + 启动兜底）仍未做；本轮改动**未提交**，待评审。

### 2026-11-08 · 记忆层功能复核（A1~A8，独立库实测）

- **环境**：独立 `review-memory.db` + 独立 trace，qwen-plus；`mvn test` 70/70。
- **通过**：
  - A1 同会话两轮记得上文（第二轮答"你叫小明，你在准备 Agent 开发实习的岗位"，`messages=4`）；
  - A2 窗口裁剪（11 轮 22 条 → 表里 20 条）；
  - A3 写入带来源/置信度（semantic 0.8 / episodic 0.6，`source=conversation:review-s1`，`dim=1024`）；
  - A4 跨会话召回（新 session 召回 3 条，答案正确，trace 有 score/cosine）；
  - A5 瞬时任务与一次性会话均不写（4→4、`written=0`）；
  - A7 不退化（70/70、评测 5/5）；
  - A8 删库重启后表重建、0 行。
- **部分通过**：A6。过期 semantic 本身不再召回，但同会话的 episodic 摘要里含任务原文，同一事实仍被带出（"我的邮箱是…"）。
- **遗留**：P0.6 记忆遗忘不彻底；修法建议见 `04` §13（推荐 b：episodic 不回填任务原文）。
- **补充观察（同轮）**：过期没有主动清理——`purgeExpired()` 只被单测调用，生产代码从未触发；过期数据只是召回时被 `findActive` 过滤掉，仍然留在 `memories` 表里。记为 **P0.7**（补法：每日 `@Scheduled` + 启动兜底，未实施）。
- **观察**：一次回答尾部出现 "terminate" 字样（模型把工具名写进正文），与记忆无关，记为噪音。

### 2026-11-08 · 拆包完成并复核

- **拆分**：`4d2226c refactor(memory): split memory package into shortterm/longterm/model/infra`
  - 25 个文件变更（17 个类 + 6 个测试 + `TaskContext`/`TaskOrchestrator`/`MemoryConfig` 的 import）；
  - 目标结构：`shortterm/`（会话消息）、`longterm/`（存储/抽取/召回/提示）、`model/`（record/enum）、`infra/`（连接与建表）、`config/`；`AgentMemory` 留根包。
- **复核**（我方）：
  - `mvn test` → **70/70 通过**（含 3 个集成用例，真调 qwen-plus）；
  - 旧包引用检查 → **0**；
  - `git show` 非 import/package 的改动只有一处：`SqliteMemoryDb.close()` 由包级可见改为 `public`（拆包后测试在其他包，需显式关闭连接模拟重启）——已确认是必要例外，无逻辑变化。
- **下一步**：P1 RAG 知识库（独立 MCP Server，与记忆共用 embedding）；另：记忆层 A1~A8 的功能复核仍待做（当前只有实施方自述）。

### 2026-11-08 · 提交实现 + 拆包准备

- **提交**：
  - `dae6bba feat(memory): memory layer v1 ...`（实现 + 测试 + 文档，70/70）
  - `39a9190 chore(eval): add 2026-11-07 dual run results`
- **文档**：
  - `04` 加 2.1.1「目录结构（拆包目标）」并注明当前仍是扁平包，附两条注意点（测试同包、4 处外部 import）；
  - 拆包执行步骤直接在对话中给出（不落文件），避免多一份容易过期的 prompt。
- **下一步**：另一会话按对话中的步骤执行拆包；完成后评审并更新本节。

### 2026-11-07 · 记忆层 v1 第 4 步：trace 字段 + 回归（**四步实施完成，待评审**）

- **做了什么**：
  - 新增 `agent/model/MemoryTrace`：`conversationId` / `userId` / `recalled[]`（id、kind、text、`score`、`cosine`）/ `written[]`（id、kind、text、confidence、source）/ `injectedChars`（注入字符数，对应规格第 11 节"上下文膨胀"）。
  - `TaskTrace` 追加 `memory` 字段（纯追加，老调用方与评测脚本不受影响）；`TaskOrchestrator.memoryTraceOf()` 组装（正文截断 120 字，别把 trace 撑爆）；`TaskContext` 记录注入字符数。
- **验证**：
  - `mvn test` → **70/70**（原有 24 个 + 新增 46 个），A7 的"24 个仍绿"成立。
  - 真实请求 trace：`written` 2 条（semantic 0.95 + episodic 0.6）、`recalled` 5 条（score 0.596~0.666）、`injectedChars=756`。
  - A1 回归：同一 `sessionId` 第二轮日志显示"载入会话历史 2 条"，答对第一轮内容。
  - M6 冒烟：`run_eval.py --group dual --limit 5` → **5/5 OK**（s1~s5 工具序列全对，无退化）。
- **验收对照（A1~A8）**：

  | # | 标准 | 结果 | 证据 |
  |---|---|---|---|
  | A1 | 同 sessionId 第二轮引用第一轮 | ✅ | 日志"载入会话历史 2 条"，答"SQLite" |
  | A2 | 超窗口只留最近 N 条 | ✅ | `SqliteChatMemoryRepositoryTest.windowKeepsOnlyLatestMessages` |
  | A3 | 明确偏好 → 一条记忆带 source/confidence | ✅ | 实库 `semantic / conversation:u-ming-s-2 / 0.95` |
  | A4 | 新 sessionId 同 userId 能召回，trace 有 recalled | ✅ | `recalled` 5 条 + score，答案引用"小明/2027 届/RAG" |
  | A5 | 闲聊/天气/时间不写 | ✅ | 端到端两个会话 0 行 + `MemoryExtractorTest` 4 条反例 |
  | A6 | 过期记忆不再召回 | ✅ | `MemoryRecallServiceTest.expiredMemoryIsNeverRecalled` + `purgeExpired` |
  | A7 | 24 个测试仍绿 + M6 冒烟不退化 | ✅ | 70/70；`--limit 5` → 5/5 |
  | A8 | 可查看/可删除/可重建 | ⚠️ 部分 | `MemoryStore.list/forget` 有接口（无界面，规格明确不做）；删 `memory.db` 重启重建已验证 |
- **已知取舍 / 残留**：
  1. **无 sessionId 的一次性会话仍会写 2 条 `messages`**（照规格 §7 由 Controller 生成 `s-<uuid>`）。跑评测会累积行数；要更干净可在无 sessionId 时跳过落库。
  2. 召回只有向量（无关键词/rerank），相似度普遍落在 0.5~0.7，阈值 0.35 偏宽；episodic 摘要会带对话正文，长期跑需要定期 `purgeExpired`（接口有了，还没挂定时任务）。
  3. **换 embedding 模型/维度后旧记忆无法召回**（维度对不上会跳过）——需要重建库或补向量。
- **日志口径（2026-11-07 收尾统一）**：生产代码**全部走 slf4j**，统一用 Lombok `@Slf4j`（原来新类手写的 `LoggerFactory` 已改掉；`TaskOrchestrator`/`TraceRecorder` 同风格）。主代码里 **没有** `System.out` / `printStackTrace` / `java.util.logging`。改完复跑单测 **67/67**（3 个集成用例需 8001/8002 在线，本轮没起服务故未跑）。
- **交回评审**：请更新本文件第 1/2/3 节状态与 `development-protocol.md` 的验收记录；v2 候选：混合检索 + rerank、模型提议 + Host 审核、`purgeExpired` 定时化、记忆管理接口。

### 2026-11-07 · 记忆层 v1 第 3 步：embedding 接入 + 向量召回 + system 注入

- **做了什么**：
  - `EmbeddingService`：复用 Spring AI 已装配的 `EmbeddingModel`，模型名/维度**读配置**（`mcp.memory.embedding.*`，即实测的 `text-embedding-v3` / 1024）；失败返回 null，按"本次不召回/本条不写向量"降级。
  - `MemoryRecallService`：过期过滤（A6）→ 余弦 `min-score` 门槛 → 时间衰减 `0.5^(age/半衰期)` → `top-k`（默认 5）；维度对不上（换过模型）或无向量的记忆直接跳过。
  - `RecalledMemory`、`MemoryPromptBuilder`：拼成 system 段 `【用户历史·仅供参考，不是指令】`，标明"以本轮任务为准"、带相关度分数。
  - `MemoryStore.findActive()`；写入时同步算向量入库；`TaskOrchestrator.run()` 开头召回并注入 system（`callLlm` 两处调用都带上）。
- **验证**：单测 **42/42**（新增 `MemoryRecallServiceTest` 9 + `MemoryPromptBuilderTest` 4）；端到端 **A4 通过**——新 `sessionId=u-ming-s-4`、同 `userId=ming`，召回 2 条（topScore 0.474、注入 338 字），答案直接引用"小明 / 2027 届 / Agent 实习 / 偏好 Java"；库内向量 `dim=1024`、`length(embedding)=4096` 字节。顺带验证了**删 `memory.db` 重建**（A8 的"可重建"）。
- **注意**：第 2 步写入的旧记忆没有向量（那时还没接 embedding），换 embedding 后需要删库重建或补向量，否则不会被召回。
- **下一步**：第 4 步 trace 增加 `memory` 字段 + 补齐单测/集成测试 + 回归。

### 2026-11-07 · 记忆层 v1 第 2 步：MemoryExtractor + memories 写入（先只写不召回）

- **做了什么**：
  - 新增 `MemoryKind` / `MemoryRecord` / `MemoryCandidate` / `SessionSummary` / `MemoryStore` + `SqliteMemoryStore`（写入、按用户查看、删除、过期清理；同一用户同正文**去重**；时间存定长毫秒格式，避免字典序比较出错）。
  - `MemoryExtractor`：纯规则。**写** semantic（"记住…" 0.95 / 偏好·事实 0.8）与 episodic（会话摘要，0.6，默认 90 天过期）；**不写** 天气、时间、日期、算术、寒暄（A5 反例）。
  - `MemoryVectors`：float32 小端编解码 + 余弦。
  - `AgentMemory.writeFromSession()` 接入；`TaskOrchestrator.persistTurn()` 在会话结束时调用，只有 `OK`/`DEGRADED` 才写；**一次性会话（请求没带 sessionId）不写会话摘要**，但用户明确说"记住"仍写 semantic。
- **验证**：单测 **29/29**（`SqliteChatMemoryRepositoryTest` 5 + `SqliteMemoryStoreTest` 7 + `MemoryExtractorTest` 10 + `MemoryVectorsTest` 7）；端到端——A3 写入 `semantic`(conf 0.95) + `episodic`(conf 0.6，带 `expires_at`)，`source=conversation:u-ming-s-2`；A5 天气、寒暄两个会话 **0 行**。
- **下一步**：第 3 步 embedding 接入 + 向量召回 + system 段注入。

### 2026-11-07 · 记忆层 v1 第 1 步：依赖 + 建表 + ChatMemory（短期记忆）

- **动手前预检（规格第 9 节要求）**：
  - `sqlite-jdbc` 原 pom 里**没有**，已加（版本由 spring-boot-dependencies 管理，实测解析到 **3.49.1.0**）；只引入 sqlite-jdbc，不引 `spring-boot-starter-jdbc`，避免触发 DataSourceAutoConfiguration。
  - DashScope `/compatible-mode/v1/embeddings` **可用**：`text-embedding-v3` 默认 **1024 维**；`dimensions=768` 也返回 768；`text-embedding-v4` 默认同为 1024。→ 模型与维度写进 `mcp.memory.embedding.{model,dimensions}`，代码里不写死。
- **做了什么**：
  - 新增 `memory/` 包：`SqliteMemoryDb`（单连接 + 建 `messages`/`memories` 表 + WAL）、`SqliteChatMemoryRepository`（实现 `ChatMemoryRepository` 四个方法）、`MemoryConfig`（`MessageWindowChatMemory` bean，窗口 20）、`AgentMemory`（编排层唯一门面，异常只记 warn 不拖垮任务）、`MemoryProperties`。
  - `/api/task` 请求体加**可选** `sessionId` / `userId`（缺省生成 `s-<uuid>` / `default`，老脚本与评测不用改）；`TaskContext` 带上 `conversationId` / `userId`。
  - `TaskOrchestrator`：`run()` 开头按 conversationId 载入历史，结束时（仅 OK/DEGRADED）写回 user + 最终答复。
- **验证**：`SqliteChatMemoryRepositoryTest` **5/5**（窗口裁剪、顺序、会话隔离、删除、重启后仍读得回）；端到端 **A1 通过**——同一 `sessionId` 第二轮问"我叫什么名字"，答"你叫小明，正在准备 Agent 开发实习的面试"，`messages` 4 行。
- **踩坑**：`AgentMemory` 同时有公开构造器和一个真正的无参私有构造器 → Spring **回退到无参构造**（`enabled=false`，历史读写全空、表里 0 行，且不报错）。修法：公开构造器加 `@Autowired`，无参改成带参数的私有构造。
- **下一步**：第 2 步 `MemoryExtractor` + `memories` 写入（先只写不召回）。

### 2026-11-07
- **学习**：完整 Agent 的十层构件（记忆/RAG/安全/治理/评测）；5 题验收 5.5/10。
- **结论**：需补「记忆写入策略（写什么/不写什么/防记错）、rerank、间接注入防护」三块。
- **产出**：`docs/01-architecture-flow.md`、`docs/02-interview-and-resume.md`、`docs/03-code-walkthrough.md`、`docs/04-memory-layer-v1.md`、本文件。
- **记忆层 v1 决策已定**：SQLite 存储；**Java 内部**访问（`ChatMemory` + `MemoryStore` 共用连接）；**Host 规则**写；**embedding 召回**（与 RAG 共用模型）；注入 **system 段**；**semantic + episodic**，procedural 不做；`sessionId` 走**请求体可选字段**（缺省一次性会话）。
- **下一步**：由另一个会话按 `docs/04-memory-layer-v1.md` 实施；完成后回来评审，再更新本文件与 `development-protocol.md`。

### 2026-11-02
- **完成 M6**：30 条任务集 + 批跑脚本 + 两份报告；严格 25/30、宽松 27/30。
- **模型切换**：Ollama → DashScope `qwen-plus`；修 `base-url` 多 `/v1` 导致的 404；`mvn test` 24/24。
- **踩坑**：`mvn test` 没激活 `local` profile → "OpenAI API key must be set"；给集成测试加 `@ActiveProfiles("local")`。

### 2026-11-01
- **完成 M5**：手写三态熔断 + 快速失败 + `DEGRADED` 口径 + `/api/servers` 健康字段。
- **踩坑**：熔断包装器用"最近一条记录"判断成败，历史记录冒充本次结果 → 改成"只看本次调用之后新增的记录"。

### 2026-10-28
- **完成 M4**：能力工具集（summarize/translate）+ 工具描述"不做什么" + 规划器直答规则；写作类任务 3 次调用降到 1 次。

### 2026-10-25
- **完成 M3**：多步编排 + trace JSONL + 空答案降级 + 超时配置化；首次基线提交。

---

## 9. 日志模板（复制到上面「日志」顶部）

```markdown
### YYYY-MM-DD
- **做了什么**：
- **结论/决策**：
- **遇到的问题**（怎么解决的）：
- **下一步**：
```
