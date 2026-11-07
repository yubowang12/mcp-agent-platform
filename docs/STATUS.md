# 项目状态与进度日志

> **这是"换会话 / 换 agent"的入口文档。** 30 秒读完：项目是什么、现在到哪、下一步做什么、怎么跑、材料在哪。
>
> 维护约定：每天结束时在「日志」顶部加一条（模板在文末），状态表有变化就一起改。
> 最近更新：2026-11-07

---

## 1. 当前状态

| 项 | 状态 |
|---|---|
| 项目定位 | **求职作品集**（Agent 开发 / 后端实习），毕业设计只是可能用途之一 |
| 阶段 | **阶段一（M1~M6）已完成**；**记忆层 v1 规格已定稿，待实施**（见 `docs/04-memory-layer-v1.md`） |
| 架构 | Java（Spring AI）规划型 Host `:8090` + Python MCP 服务（工具 `:8001`、Agent `:8002`）+ DashScope `qwen-plus` |
| 测试 | **24/24**（21 单测 + 3 集成），跑在 `qwen-plus` 上 |
| 评测 | 30 条任务集：严格 25/30（83.3%）、宽松 27/30（90%）。⚠️ 这是 **Ollama 基线**，换模型后未重跑 |
| 文档 | 11 份（见第 6 节） |
| 未提交 | 无（2026-11-07 决策更新与 `docs/04` 待提交） |

## 2. 现在在做什么

- **记忆层 v1**：规格已冻结（`docs/04-memory-layer-v1.md`），**由另一个会话实施**；
- 已定：SQLite 存储、Java 内部访问、Host 规则写、**embedding 召回**（与 RAG 共用模型）、注入 system 段、semantic + episodic、`sessionId` 用请求体可选字段（缺省=一次性会话）；
- 实施完成后：由我评审 + 更新本文件与 `development-protocol.md`。

## 3. 待办（按优先级）

| 优先级 | 事项 | 说明 |
|---|---|---|
| **P0** | 记忆层 v1 | 规格见 `docs/04-memory-layer-v1.md`：SQLite + ChatMemory（短期）+ 长期记忆（embedding 召回）。**待实施** |
| **P1** | RAG 知识库 | 独立 **MCP Server**；与记忆共用同一 embedding 模型/维度 |
| **P2** | 安全 | 工具白名单、检索内容当"数据"不当指令（spotlighting）、敏感信息过滤 |
| **P3** | 工程收尾 | MCP 会话重连（重启后熔断不恢复）、CI、前端控制台 |
| **P4** | 数据 | 用 `qwen-plus` 重跑 M6；补 `dual` 组 10 条人工复核 |

## 4. 关键约束（换人做也别踩）

1. **密钥**：只放 `demo/java-mcp-client/src/main/resources/application-local.yml`（gitignored）。**不要**写进 `application.yml`。
2. **base-url**：Java 侧 `spring.ai.openai.base-url` 到 `.../compatible-mode`（**不带 `/v1`**，Spring AI 自己拼）；Python 侧 `LLM_BASE_URL` 要**带 `/v1`**。
3. **测试前提**：3 个集成用例需要 8001/8002 在线；测试类已 `@ActiveProfiles("local")`。跑 `mvn test` 前没起服务会启动失败（fail-fast，不是 bug）。
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
```

## 6. 文档地图

| 想干什么 | 看哪个 |
|---|---|
| **接手项目** | **本文件（STATUS.md）** |
| 简历怎么写、面试怎么讲 | `docs/portfolio.md` |
| 看懂系统怎么跑（图） | `docs/01-architecture-flow.md` |
| 背面试题 | `docs/02-interview-and-resume.md` |
| 逐段读代码 | `docs/03-code-walkthrough.md` |
| **实施记忆层 v1** | `docs/04-memory-layer-v1.md`（决策、表结构、验收、待定项） |
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
