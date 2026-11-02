# 开发协作协议（用户定稿 2026-10-20）

> 本协议取代此前"我直接出方案+写码"的模式，后续所有正式开发都按此流程执行。

## 协作流程（五步循环）

```
① 我：告诉用户"现在要开发什么"（目标、范围、验收标准）
② 用户：自己给出开发方案（怎么做、改哪些文件、类/接口设计）
③ 我：评审用户方案，指出不足/风险/遗漏，不直接替用户改方案
④ 用户：根据意见修改方案，改完回来问"方案怎么样？"
⑤ 我：确认通过后，执行方案（写代码/配置），用户审核代码
```

## 关键约定

1. **方案主导权在用户**：开发方案必须由用户先出，我不抢先写实现方案
2. **评审只挑毛病**：第 ③ 步我只讲不足与风险，讲清楚"为什么这是问题"
3. **执行前必须确认**：用户改完方案后问过我，我才开始写代码
4. **代码仍受"小步走"约束**：每步一个可验证增量，用户审核、自测、拍板
5. **依赖变更**：下载新依赖时我给出依赖坐标，由用户自行加入 pom（2026-10-20 约定）
6. **新技术先学后写（2026-10-20 约定）**：每开始一块需要新技术的代码前，我先给出「学习清单」（官方文档链接 + 关键概念），用户学完确认后才写代码——**严禁用户没学过就写代码**
7. **面试高频强调（2026-10-20 约定）**：面向 Agent 开发岗位，我在每个模块开始/结束时标注「面试高频技术点」，说明面试会怎么问、项目里在哪体现
8. **任务前主题介绍（2026-10-27 约定）**：每个 Mn 开始前，先给出「本次开发主题卡」——**主题 / 开发内容 / 目的 / 为什么现在做 / 不做会有什么问题**；用户了解清楚后再进入 ① 目标范围与验收标准，避免"不知道为什么写这段代码"
9. **工程问题先给提示（2026-10-31 约定）**：用户自述无实际工程基础；凡需要用户做工程/架构决策时，先解释术语（配具体报文或示例），再给 2~3 个可选项及各自代价，最后让用户选——不抛名词、不让用户凭空回答。

## Agent 开发面试高频技术点（持续更新）

| # | 高频技术 | 面试常见问法 | 本项目对应 |
|---|---|---|---|
| 1 | MCP 协议 | 为什么用 MCP 不用自定义 REST？stdio vs HTTP？ | M1 前缀化工具目录 |
| 2 | Function Calling | schema 怎么设计？并行调用？参数校验？ | M2 规划循环 + M4 工具描述工程 |
| 3 | Agent 循环（ReAct/Plan-Execute） | 规划-执行-观察怎么实现？怎么防死循环？ | M2/M3 状态机 + maxSteps |
| 4 | 多 Agent 协作 | 中心化 vs 去中心化编排？任务分解？ | 架构基线（规划型 Host） |
| 5 | 服务治理 | 超时/重试/熔断/降级怎么做？ | M5 三态熔断 + 快速失败 + 降级口径 |
| 6 | 可观测性 | trace 怎么串联？耗时怎么定 SLO？ | M3 Trace |
| 7 | 安全 | 提示注入怎么防？工具权限？沙箱？ | R3 |
| 8 | 评测 | 怎么衡量 Agent 效果？成功率/延迟？ | M6 任务集 |
| 9 | 上下文管理 | 长对话/长文档怎么处理？记忆分层？ | 后续扩展 |
| 10 | 工程实践 | 幻觉工具名怎么防？失败重试策略？ | 前缀白名单 + 降级 |

## 当前开发进度（截至 2026-10-28）

| 项 | 状态 |
|---|---|
| M1 步 1：pom + yml（Ollama starter、toolcallback、ollama 配置） | ✅ 完成（依赖用户自改） |
| M1 步 2：前缀 bean + `/api/catalog` | ✅ 完成（`demotools_add` 等已出现） |
| M1 步 3：`/api/planner/ping` 调通 Ollama | ✅ 完成（qwen2.5:3b 正常返回） |
| M1 步 4：练习 A（subtract 工具）+ 全量验收 | ✅ 完成（2026-10-22，`demotools_subtract` 自动出现在 catalog；get-time/ping/planner/ping 回归通过） |
| M2：`/api/task` 单步闭环 | ✅ 完成（2026-10-22，`TaskOrchestrator` 手动 ReAct 循环 + 45s 工具超时 + 300s 任务超时 + terminate 工具；单步任务测试跑通） |
| M3：多步编排 + trace 落盘 | ✅ 完成（2026-10-25：多步任务 + JSONL 落盘 + 空答案降级 + LLM 60s 超时配置化 + 测试升级；**2026-10-28 全量回归 `mvn test` 3/3 通过**） |
| M4：Agent 能力工具集 | ✅ 完成（2026-10-28：新增 `summarize`/`translate`、`run_task` 降级为兜底、规划器加"自答"规则；工具选择 5/5 命中，`mvn test` 4/4 通过；详见下节验收记录） |
| M5：熔断/降级/故障注入 | ✅ 完成（2026-11-02：手写三态熔断器 + 快速失败 + `DEGRADED` 口径 + `/api/servers` 健康字段；`mvn test` 24/24；已知限制见验收记录） |

> 2026-10-22 备注：M1 关闭时观察到一个待改进点——`/api/servers` 返回的 version 为空（`Implementation.version()` 在 Python 端未上报）。不影响功能，登记到后续待办。
>
> 2026-10-28 复核：当前构建全量测试 4/4 通过（`TaskOrchestratorTest` 3 个集成用例 + `NoToolsAvailableTest` 1 个纯单测），001/002 分别耗时 2.4s/3.3s，trace 落在 `demo/logs/trace/2026-10-28.jsonl`。
>
> 2026-10-26 曾出现一条 `taskId=001` 的 TIMEOUT trace（总耗时 68min、记录到的 LLM 耗时 626s、`errorCode` 为空）。该记录不符合当前 `LlmTimeoutException` 分支（当前超时应写 `LLM_TIMEOUT`），更像是旧进程/旧构建未加载新类所致；10-28 用当前代码复核已通过。后续跑评测前先确认 Java 进程是最新构建，并预热 7b 模型（本次预热约 13.6s）。

## M3 收尾验收标准（已完成 2026-10-25；2026-10-28 回归通过）

1. ✅ **空答案不以 OK 落盘**：空答案重试一次；仍空则回填最后一条工具结果，`status=DEGRADED`、`errorCode=EMPTY_ANSWER`。
2. ✅ **超时与配置化**：LLM 调用 60s 超时（`LlmTimeoutException` → `TIMEOUT` + `LLM_TIMEOUT`）；`mcp.planner.*` 配置化（model / task-timeout / llm-timeout / tool-timeout）。
3. ✅ **测试升级**：`TaskOrchestratorTest` 断言 `status/usedSteps/finalAnswer`；新增 `NoToolsAvailableTest`（Mockito 纯单测）。2026-10-28 `mvn test` 全量 3/3 通过。

## M4 验收记录（已完成 2026-10-28）

**目标**：把 Agent 的"万能入口" `run_task` 拆成能力工具集，验证规划模型按 `description` 选中专用工具，而不是盲选。

**交付**

1. `demo/py-agent/agent.py`：新增 `summarize(text, max_words=100)`、`translate(text, target_lang)`；`run_task` 降级为兜底；四个工具都带 `title` 与"做什么 / 不做什么"描述（描述用模块常量，避免 `__doc__` 缩进污染发给模型的文本）。
2. 规划器系统提示（`TaskOrchestrator`）新增两条规则：**能自己完成的写作/解释/建议类任务直接回答，不调用工具**；**最终答复只给结果，不复述输入**。
3. `TaskOrchestratorTest.run3`：自我介绍类任务断言 `usedSteps() < 2`，钉住"该直答就直答"。
4. `test_agent_client.py`：自测升级为工具清单 + 摘要 + 双向翻译 + ping + run_task 回归，支持 `AGENT_URL` 覆盖端口。

**验收数据（2026-10-28 实测，7b 规划）**

| # | 任务 | 期望 | 实际工具序列 | 步数 | 耗时 |
|---|---|---|---|---|---|
| 1 | 现在几点 | `get_time` | `demotools_get_time` | 1 | 2.3s |
| 2 | 10-1+2（先减后加） | `subtract`→`add` | `demotools_subtract`→`demotools_add` | 2 | 3.5s |
| 3 | 压缩成不超过 50 字的要点 | `summarize` | `agentworker_summarize` | 1 | 9.1s |
| 4 | 翻译成英文 | `translate` | `agentworker_translate` | 1 | 4.3s |
| 5 | 写一份求职自我介绍 | 直答、不调工具 | 无工具，规划器直答 | 0 | 16.5s |

- `GET /api/catalog`：7 个工具，`agentworker_summarize` / `agentworker_translate` 前缀化正确，4 个工具含"不做什么"边界说明。
- `mvn test`：**4/4 通过**（`TaskOrchestratorTest` 3 + `NoToolsAvailableTest` 1）。
- 自我介绍类任务经"自答"规则修正后：**3 次模型调用 / 35s → 1 次 / 8~16s**；此前还出现过"生成完再调 summarize 摘要自己"的 5 次调用链，随规则修正消失。

**关键决策：规划器优先（方案 B）**

`run_task` 原本的描述是"除专用工具外都归我"，导致写作类需求必然被委派给执行器（多两次模型调用、质量还从 7b 掉到 3b）。改为"能自己完成的写作/解释/建议类任务直接回答"后，工具表实际收敛为"专用能力才调工具"。M6 的"该直答却委派"因此从"无从判定"变成可计量的错选。

**已知限制（进 M6 前要处理）**

1. `max_words` 只是提示性约束：3b 实测 30→121 字、50→96~107 字；7b 30→77 字、50→77 字。未做截断/重试（按"基础版本不加规则兜底"约定）。
2. 执行器默认模型已由 `qwen2.5:3b` 改为 `qwen2.5:7b`（`LLM_MODEL` 环境变量可切回），与 `architecture.md` 6.2 的"7b 规划 + 3b 执行"暂时不一致，M6 实验前统一定标。
3. `run_task` 在 5 条样本中已不再被选中，"是否从目录摘除、只留作降级兜底"留作 M6 对照组问题。

**面试高频点**：工具描述怎么写（"不做什么"、默认值、边界反例）→ `agent.py` 三个描述常量；怎么判断工具选错 → M4 的五条样本 + trace 的 `steps[].tool`；"该直答却委派"为什么是问题 → 本节 3 次/35s vs 1 次/16s 的对比。

## M5 决策记录（进行中，2026-10-31）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 状态与原因的分工 | `status` 表结果（`OK`/`DEGRADED`/`ERROR`/`MAX_STEPS`/`TIMEOUT`），`errorCode` 表原因；不需要单独的"降级返回码" |
| 2 | 何时记 `DEGRADED` | **任一步骤失败即 `DEGRADED`**（用户定：否则只要有产出就看不出故障）；`errorCode` 取第一个失败步骤的 code；`OK` 因此蕴含"全部步骤成功" |
| 3 | 两个失败码的分工 | `TOOL_ERROR` = 真调用失败；`CIRCUIT_OPEN` = 被熔断器拦截（`latencyMs` 接近 0，是熔断生效的证据） |
| 4 | 熔断器位置 | 新包 `com.example.mcpdemo.resilience`；`CircuitBreakerRegistry` 按 server 存状态，同 server 的工具共用一个熔断器 |
| 5 | 失败判定 | 以 MCP `isError` + Java 异常为准（已去掉字符串前缀判断）；空结果不算失败；`TraceStep` 新增 `errorCode` |
| 6 | 半开触发 | **不做定时任务**（用户定 A）：冷却到期后由下一个真实请求在 `tryAcquire()` 内完成 `OPEN → HALF_OPEN`，并发额度 1 |
| 7 | 失败计数策略 | 连续失败计数（成功即清零），M5 不换滑动窗口。代价：`F F S F F S…` 交替的翻转服务永远凑不够阈值、不会熔断——列入已知限制，M6 做"连续计数 vs 滑动窗口"对照实验 |
| 8 | 目录过滤 | **不摘除**（用户定 A）：熔断中的工具仍在目录里，靠调用时快速拒绝（`CIRCUIT_OPEN`，`latencyMs≈0`）；`/api/servers` 只标 `healthy=false`。原因：摘除 + 惰性探测会导致"永远收不到请求、永远不恢复" |
| 9 | 故障注入 | M5 用"停/起进程"（手工或脚本）+ 单测桩；Python 侧 `FAULT_MODE` 开关留到 M6 做批量评测时再加 |

## M5 验收记录（已完成 2026-11-02）

**目标**：停掉任意一个依赖，`/api/task` 不返回 5xx；熔断打开后调用**快速失败**而不是等 45s 超时；trace 能看出“哪一步失败、为什么”。

**交付**

1. `resilience/`：手写三态熔断器（`CircuitState` / `CircuitBreaker` / `CircuitBreakerRegistry` / `CircuitBreakerProperties`），注入 `Clock` 便于测试，状态按 server 隔离。
2. `agent/`：`TimeoutToolCallback`（超时 + 记录）、`CircuitBreakingToolCallback`（熔断 + 快速拒绝）、`ToolCallRecorder`（结构化成败旁路）；工具链为 `MCP → 超时 → 熔断`。
3. 失败判定改为 MCP `isError` / Java 异常，删除字符串前缀判断；`TraceStep` 增加 `errorCode`。
4. `DEGRADED` 口径落地：任一步骤失败且仍有答案 → `DEGRADED` + 第一个失败步骤的 `errorCode` + `degradedSteps`。
5. `/api/servers` 暴露 `healthy` / `state` / `consecutiveFailures`。

**验收数据（2026-11-02 实测，冷却临时配成 5s）**

| 场景 | 结果 |
|---|---|
| 基线“现在几点” | `OK`，1 步，48ms |
| 停 8001 后第 1~3 次 | `DEGRADED`，`TOOL_ERROR`，步骤 6~7ms 失败，答案降级为“当前时间无法获取” |
| 3 次之后 | `/api/servers`：`healthy=false`、`state=OPEN`、`consecutiveFailures=3` |
| 冷却到期后再调 | 探测失败（`TOOL_ERROR`），回到 `OPEN` |
| 紧接着再调 | `CIRCUIT_OPEN`，`latencyMs=0`，提示“服务熔断中…”——**快速失败成立** |
| `mvn test` | **24/24 通过**（21 单测 + 3 集成） |

**已知限制**

1. **服务重启后不能自动恢复**：Java 持有的 MCP 会话失效（`MCP session with server terminated`），半开探测必然失败，状态停在 `OPEN`；需要重启编排层重新握手。“重连”留到 R1（注册中心那步本来就要重建连接管理）。
2. **翻转服务不会被熔断**：连续失败 + 成功清零，`F F S F F S…` 永远凑不够阈值（见决策记录 7），M6 做“连续计数 vs 滑动窗口”对照。
3. **熔断中工具仍在目录里**（决策 8）：靠调用时拒绝，不靠摘除；模型可能撞一次墙再换工具。

**面试高频点**：三态怎么转、半开为什么只放 1 个、为什么“熔断在外、超时在内”、`TOOL_ERROR` 与 `CIRCUIT_OPEN` 的区别（后者 `latencyMs≈0` 就是证据）、为什么 `DEGRADED` 要按“任一步失败”算。

## 后续安排

1. ~~基线整理~~（✅ 2026-10-25：`.gitignore` + 首次提交 + 文档回填）
2. ~~M3 收尾~~（✅ 2026-10-25）
3. ~~补课验收~~（✅ 2026-10-27：U4/U6 均通过，8/10；记录见 `docs/learning-plan.md`）
4. ~~M4 能力工具集~~（✅ 2026-10-28：见上节验收记录）
5. ~~M5 熔断/降级/故障注入~~（✅ 2026-11-02：见上节验收记录）
6. 下一步 **M6**（30 条任务集评测）；开始前按约定 8 先出主题卡。
