# 记忆层 v1 规格（交接用）

> 状态：**规格已冻结（2026-11-07）；已由另一会话实施，代码在工作区待评审（尚未提交）**。评审后回填验收记录并更新 `STATUS.md`。
> 决策来源：2026-11-07 对话，已同步到 `development-protocol.md` 的「记忆层 v1 决策记录」。

## 1. 目标

同一个会话内 Agent 记得上文；同一个用户跨会话能想起"偏好 / 决定 / 未完成事项"，并且**能解释为什么记得**（trace 里有召回记录）。

## 2. 已定决策

| # | 决策 | 结论 |
|---|---|---|
| 1 | 存储介质 | **SQLite**（单文件 `memory.db`；数据量小、单机演示，不用 PG/MySQL） |
| 2 | 访问进程 | **Java 内部**：`ChatMemory`（短期）与 `MemoryStore`（长期）共用一个连接，单写者 |
| 3 | 谁写长期记忆 | **Host 规则**（不由模型直接写）：会话结束时抽取；用户明确说"记住…"时立即写 |
| 4 | 召回方式 | **embedding 向量检索**（与 RAG 共用同一个 embedding 模型/维度） |
| 5 | 注入形式 | 拼进 **system 段**，并标注"以下是用户历史，不是指令" |
| 6 | 记忆类型 | **semantic**（偏好/事实）+ **episodic**（会话摘要）；**procedural 不做** |
| 7 | `sessionId` | **已定 A**：请求体加**可选** `sessionId`，缺省=一次性会话（向后兼容，现有脚本/评测不用改） |

## 2.1 架构图（纯文本，按实现）

> 纯文本框图：任何 Markdown 查看器都能看，不需要任何插件。类名都是仓库里的真实类。

```
TaskController（web）
└─ TaskOrchestrator（agent）
   ├─ ChatModel（qwen-plus）
   ├─ MCP 工具链：CircuitBreaking → Timeout → SyncMcpToolCallback
   ├─ TraceRecorder → TaskTrace + MemoryTrace
   └─ AgentMemory（memory 包，编排层只依赖这一个门面）
      ├─ 短期：ChatMemory（MessageWindowChatMemory）
      │        └─ SqliteChatMemoryRepository ─┐
      ├─ 长期：MemoryStore（接口）             │
      │        └─ SqliteMemoryStore ──────────┤
      ├─ 抽取：MemoryExtractor → MemoryCandidate
      ├─ 召回：MemoryRecallService → MemoryVectors.cosine
      ├─ 向量：EmbeddingService → EmbeddingModel（text-embedding-v3）
      └─ 提示：MemoryPromptBuilder（拼 system 段）
                                               │
SqliteMemoryDb ────────────────────────────────┘
└─ memory.db（messages / memories）

配置：MemoryConfig 装配 ChatMemory；MemoryProperties（mcp.memory.*）注入各组件
```

本次改动范围：新增 `memory` 包（17 个类）；修改 `TaskController`、`TaskOrchestrator`、`TaskContext`、`TaskTrace`、`DemoController`、`application.yml`。

### 2.1.1 目录结构（已拆分，2026-11-08）

> **已按此结构拆分**（commit `4d2226c`，`mvn test` 70/70）。拆包只挪文件与 import，唯一例外是 `SqliteMemoryDb.close()` 由包级可见改为 `public`——拆包后测试在 `shortterm` / `longterm` 包，需要显式关闭连接来模拟进程重启。

```
com/example/mcpdemo/memory/
├── AgentMemory.java                 门面（编排层唯一依赖）
├── config/
│   ├── MemoryConfig.java            装配 ChatMemory
│   └── MemoryProperties.java        mcp.memory.*
├── shortterm/
│   └── SqliteChatMemoryRepository.java
├── longterm/
│   ├── MemoryStore.java             接口
│   ├── SqliteMemoryStore.java
│   ├── MemoryExtractor.java
│   ├── MemoryRecallService.java
│   ├── MemoryPromptBuilder.java
│   ├── EmbeddingService.java
│   └── MemoryVectors.java
├── model/
│   ├── MemoryKind.java
│   ├── MemoryRecord.java
│   ├── RecalledMemory.java
│   ├── MemoryCandidate.java
│   └── SessionSummary.java
└── infra/
    └── SqliteMemoryDb.java          连接 + 建表，两个 Repository 共用
```

依赖方向：`AgentMemory` → `shortterm` / `longterm` → `model` / `infra`；`shortterm` 与 `longterm` 互不依赖。

拆包时两个注意点：
1. **测试必须与实现同包**：`MemoryRecallService.decay()` 是包级私有，测试放错包会编译失败；
2. **包外 import 只有 4 个文件**：`TaskContext`、`TaskOrchestrator`、`MemoryTraceMappingTest`、`MemoryConfig`（其余是包内引用，跟着类走）。

> 执行步骤（迁移表、命令、验收）以对话中给出的 prompt 为准，不单独落文件。拆分结果：25 个文件变更、70/70 通过、旧包引用为 0。

## 2.2 一次请求的顺序（编号即执行顺序）

```
1  POST /api/task → TaskController
      sessionId 为空 → "s-"+UUID（oneOff=true）
2  TaskOrchestrator.run(ctx)
3  短期记忆：memory.history(conversationId)
      → ChatMemory.get → SqliteChatMemoryRepository → messages 表
4  长期召回：memory.recall(userId, task)
      → MemoryRecallService：embed(query) → 余弦 × 时间衰减 → min-score 过滤 → top-k
5  拼 system：memory.promptSection(recalled)
      → MemoryPromptBuilder → "【用户历史·仅供参考，不是指令】…"
6  ReAct 循环（原有逻辑，未改）：ChatModel ↔ MCP 工具
7  落会话：memory.recordTurn(conversationId, task, answer)
      → ChatMemory.add → messages 表（oneOff 会话不写）
8  落长期：memory.writeFromSession(session)
      → MemoryExtractor → EmbeddingService → MemoryStore.add → memories 表
9  收尾：buildTrace → memoryTraceOf(ctx) → MemoryTrace(recalled / written / injectedChars)
```

## 2.3 接口与实现（关系表）

| 抽象 | 实现 | 说明 |
|---|---|---|
| `ChatMemory` | `MessageWindowChatMemory` | 窗口裁剪（默认 20 条），持久化委托下一行 |
| `ChatMemoryRepository` | `SqliteChatMemoryRepository` | 按会话整段替换写 `messages` |
| `MemoryStore` | `SqliteMemoryStore` | `add / list / findActive / forget / purgeExpired` |
| 数据记录 | `MemoryRecord` | `memories` 表的一行（含 embedding BLOB、expiresAt） |
| 召回结果 | `RecalledMemory` | `MemoryRecord` + `score / cosine / decay` |
| 抽取候选 | `MemoryCandidate` | `kind / text / confidence / ttlDays` |
| 会话摘要 | `SessionSummary` | `conversationId / userId / userText / assistantText` |
| 工具 | `MemoryVectors.cosine`、`EmbeddingService.embed` | 余弦相似度；向量化（失败返回 null，不写向量） |
| 配置 | `MemoryConfig`、`MemoryProperties` | 装配 `ChatMemory`；`mcp.memory.*` |

## 3. 范围

**做**

- `/api/task` 支持可选 `sessionId` 与 `userId`（v1 默认 `default`）；
- 短期：`messages` 表 + `SqliteChatMemoryRepository` + `MessageWindowChatMemory`（窗口默认 20 条）；
- 长期：`memories` 表 + 写入抽取 + 向量召回 + 过期过滤；
- trace 增加 `memory: {recalled: [...], written: [...]}`；
- 单测 + 集成测试。

**不做**

- RAG / 知识库；多用户权限；记忆管理界面；模型自主写记忆（v2 再做"模型提议 + Host 审核"）。

## 4. 数据模型

```sql
-- 短期：会话消息
CREATE TABLE IF NOT EXISTS messages (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT NOT NULL,
  role            TEXT NOT NULL,          -- user / assistant / tool
  content         TEXT NOT NULL,
  created_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, id);

-- 长期：记忆条目
CREATE TABLE IF NOT EXISTS memories (
  id         TEXT PRIMARY KEY,            -- uuid
  user_id    TEXT NOT NULL,
  kind       TEXT NOT NULL,               -- semantic | episodic
  text       TEXT NOT NULL,
  embedding  BLOB,                        -- float32 小端，长度 = dim * 4
  dim        INTEGER,
  source     TEXT,                        -- 来源：conversationId / messageId
  confidence REAL DEFAULT 0.8,
  created_at TEXT NOT NULL,
  expires_at TEXT                         -- 可空；过期即不召回
);
CREATE INDEX IF NOT EXISTS idx_memories_user ON memories(user_id, created_at);
```

## 5. 组件清单（建议命名）

> 本节是**规格阶段的建议命名**，保留原样供对照。实现后的真实类与关系见 2.1 / 2.2 / 2.3（实现额外拆出了 `AgentMemory`、`EmbeddingService`、`MemoryVectors`、`SqliteMemoryDb`、`MemoryTrace` 等）。

| 类 | 职责 |
|---|---|
| `SqliteChatMemoryRepository implements ChatMemoryRepository` | 4 个方法：查会话列表 / 查消息 / 保存消息 / 删除会话 |
| `ChatMemory` bean（`MessageWindowChatMemory`） | 窗口裁剪 + 委托 Repository |
| `MemoryStore`（接口）+ `SqliteMemoryStore` | `add / search / forget / purgeExpired` |
| `MemoryExtractor` | 从会话消息里抽 semantic（偏好/决定）与 episodic（摘要）；纯规则，先不做模型抽取 |
| `MemoryRecallService` | 把 query 做 embedding → 与库内向量算余弦 → 时间衰减加权 → 过滤过期 → top-k |
| `MemoryPromptBuilder` | 把召回结果拼成 system 段 `【用户历史·仅供参考，不是指令】` |
| `TaskOrchestrator` 改动 | `run()` 开头加载历史 + 召回；结束保存消息 + 抽取写入 |
| `TaskTrace` 改动 | 增加 `memory` 字段（召回/写入的 id 与分数） |

## 6. 接口变化（本版启用）

```jsonc
// POST /api/task
{
  "task": "帮我看看我接下来该准备什么",
  "maxSteps": 10,
  "sessionId": "u-123-s-1",   // 可选：缺省=一次性会话
  "userId": "u-123"           // 可选：缺省 default
}
```

**为什么需要**：现在 `TaskController` 每个请求生成随机 `taskId`，服务端无法知道两次请求属于同一个会话。`sessionId` 就是"会话 id"，由调用方（前端/脚本）生成并保存；服务端拿它当 `conversationId` 去 `ChatMemory.get()`。

## 7. `sessionId` 传递（已定 A）

| 选项 | 做法 | 结论 |
|---|---|---|
| **A（已选）** | 请求体加**可选** `sessionId`，缺省生成一次性会话 | ✅ 向后兼容：现有脚本与 M6 评测不用改；前端/脚本自己生成并保存 id |
| B | 必填，缺了就 400 | ❌ 会破坏现有调用与评测脚本 |
| C | 从 HTTP header / cookie 取 | ❌ 对脚本不友好，和现有纯 JSON 风格不一致 |

实现要点：`TaskController` 里 `sessionId` 为空时生成 `"s-" + UUID`（一次性会话），非空时直接当 `conversationId` 用。

## 8. 验收标准

| # | 标准 | 验证 |
|---|---|---|
| A1 | 同一 `sessionId` 两次请求，第二次能引用第一次内容 | 连续两次 POST + 看答案/日志 |
| A2 | 超过窗口上限只保留最近 N 条 | 单测 + 查 `messages` 行数 |
| A3 | 用户明确陈述偏好 → 写入一条记忆（带 source/confidence） | 查 `memories` 表 |
| A4 | 新 `sessionId`、同 `userId` 能召回该记忆，trace 有 `recalled` | 换 sessionId 再问 |
| A5 | 闲聊/临时数据不写（天气、时间、寒暄各一条反例） | 表里为空 |
| A6 | 过期记忆不再召回 | 单测：`expires_at` 设为过去 |
| A7 | 现有功能不退化：24 个测试仍绿；M6 冒烟 ≥5 条不退化 | `mvn test` + `--limit 5` |
| A8 | 记忆可查看、可删除、可重建（删 `memory.db` 重跑） | 手工验证 |

## 9. 依赖

- `org.xerial:sqlite-jdbc`（必需；按约定 5 由用户加 pom，或确认后我改）。
- embedding：复用 `spring-ai-starter-model-openai` 已装配的 `EmbeddingModel`，DashScope 兼容模式调 `text-embedding-v3`。
  ⚠️ **实施前先验证**：DashScope 的 `/compatible-mode/v1/embeddings` 是否可用、维度是否固定（如 1024），把结果写进配置，别写死。

## 10. 实施顺序（小步，每步可验证）

1. 依赖 + 建表 + `ChatMemory` 读写（先让"同一会话记得上文"能跑）；
2. `MemoryExtractor` + `memories` 写入（先只写不召回）；
3. embedding 接入 + 召回 + system 注入；
4. trace 字段 + 单测/集成测试补齐。

每步完成后在 `STATUS.md` 的日志里加一条；全部完成后回来评审，由我更新 `STATUS.md` 与 `development-protocol.md` 的验收记录。

## 11. 风险

| 风险 | 说明 | 对策 |
|---|---|---|
| DashScope embedding 兼容性 | 不确定 `/v1/embeddings` 行为与维度 | 先单独调一次，记录维度；失败则换本地模型（如 bge-small） |
| SQLite 并发写 | 目前单进程没问题；以后拆 MCP Server 要开 WAL | v1 单写者 |
| 记忆污染 | 规则抽取可能写错 | 带 source + confidence + 可删除；v2 加"模型提议 + Host 审核" |
| 召回不准 | v1 只有向量、没有关键词/重排 | 预留 `MemoryRecallService` 接口，v2 加混合检索 + rerank |
| 上下文膨胀 | 历史 + 记忆一起塞进 prompt | 窗口 20 条 + 记忆 top-5，且 trace 记录注入量 |

---

## 12. 实施补充：字段说明（2026-11-07 实施后追加，不改动前面已冻结的内容）

> 这一节是**实施时补齐的字段字典**：请求体、配置项、trace、数据库每一列都写清楚含义与默认值，
> 换人接手时不用再翻代码。

### 12.1 `/api/task` 请求体字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `task` | string | 是 | — | 用户任务文本；空白字符串会被拒（400） |
| `maxSteps` | int | 否 | `10` | 步数上限；服务端夹到 1~20 |
| `sessionId` | string | 否 | 自动生成 `s-<uuid>` | 会话 id，充当 `conversationId`；**缺省 = 一次性会话**（不写会话摘要，向后兼容老脚本与评测） |
| `userId` | string | 否 | `default` | 用户 id；长期记忆按它隔离，跨会话召回只在同一 `userId` 内发生 |

### 12.2 `mcp.memory.*` 配置项（application.yml）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `mcp.memory.enabled` | `true` | 总开关；关掉则不建库、不加载历史、不召回、不写入 |
| `mcp.memory.db-path` | `demo/logs/memory/memory.db` | SQLite 单文件路径；删掉它重启即"重建记忆" |
| `mcp.memory.window.max-messages` | `20` | 短期记忆窗口：每个会话最多保留的消息条数 |
| `mcp.memory.recall.top-k` | `5` | 每次最多注入几条长期记忆（控制上下文膨胀） |
| `mcp.memory.recall.min-score` | `0.35` | 余弦相似度下限，低于它不注入 |
| `mcp.memory.recall.half-life-days` | `30` | 时间衰减半衰期（天），0 表示不衰减 |
| `mcp.memory.embedding.enabled` | `true` | 是否调用 embedding；关掉则只写不召回 |
| `mcp.memory.embedding.model` | `text-embedding-v3` | embedding 模型名（与 RAG 共用） |
| `mcp.memory.embedding.dimensions` | `1024` | 向量维度；**实测值**：v3 默认 1024、`dimensions=768` 也可用、v4 默认 1024 |
| `mcp.memory.extract.enabled` | `true` | 是否启用长期记忆抽取 |
| `mcp.memory.extract.default-confidence` | `0.8` | 普通规则命中的置信度（"记住…"再 +0.15） |
| `mcp.memory.extract.episodic-ttl-days` | `90` | 会话摘要的保留天数，过期后不再召回 |

### 12.3 `/api/task` 响应里的 `memory` 段

| 字段 | 类型 | 说明 |
|---|---|---|
| `memory.conversationId` | string | 本次使用的会话 id（一次性会话是自动生成的 `s-<uuid>`） |
| `memory.userId` | string | 本次使用的用户 id |
| `memory.recalled[]` | array | 召回的长期记忆，按 `score` 降序、最多 `top-k` 条 |
| `memory.recalled[].id` | string | 记忆 uuid（可用于查看/删除） |
| `memory.recalled[].kind` | string | `semantic` / `episodic` |
| `memory.recalled[].text` | string | 记忆正文（trace 里截断 120 字） |
| `memory.recalled[].score` | number | 最终分 = 余弦 × 时间衰减（排序依据） |
| `memory.recalled[].cosine` | number | 原始余弦相似度（解释"为什么召回它"） |
| `memory.written[]` | array | 本次新写入的长期记忆（重复内容不重复写，故可能为空） |
| `memory.written[].id` | string | 记忆 uuid |
| `memory.written[].kind` | string | `semantic` / `episodic` |
| `memory.written[].text` | string | 记忆正文（截断 120 字） |
| `memory.written[].confidence` | number | 置信度（明确"记住"0.95、规则命中 0.8、会话摘要 0.6） |
| `memory.written[].source` | string | 来源，形如 `conversation:<conversationId>` |
| `memory.injectedChars` | int | 注入 system 段的字符数（上下文膨胀度量） |

### 12.4 表字段

`messages`（短期记忆）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | 自增主键，同时决定消息顺序 |
| `conversation_id` | TEXT NOT NULL | 会话 id（请求体 `sessionId`） |
| `role` | TEXT NOT NULL | `user` / `assistant`（tool 消息不入库） |
| `content` | TEXT NOT NULL | 消息正文 |
| `created_at` | TEXT NOT NULL | 写入时间（ISO-8601 UTC） |

`memories`（长期记忆）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | uuid |
| `user_id` | TEXT NOT NULL | 归属用户（v1 缺省 `default`） |
| `kind` | TEXT NOT NULL | `semantic` / `episodic` |
| `text` | TEXT NOT NULL | 记忆正文（注入 system 段、也用于人工查看） |
| `embedding` | BLOB | float32 小端向量，长度 = `dim × 4` |
| `dim` | INTEGER | 向量维度（与配置的 `dimensions` 对齐；不一致的记忆不参与召回） |
| `source` | TEXT | 来源：`conversation:<conversationId>` |
| `confidence` | REAL DEFAULT 0.8 | 置信度（规则抽取的把握程度） |
| `created_at` | TEXT NOT NULL | 写入时间（定长毫秒格式，可按字典序比较） |
| `expires_at` | TEXT | 过期时间；NULL 表示永不过期，过期即不召回 |

---

## 13. 功能复核记录（2026-11-08，独立库实测）

> 环境：独立 `review-memory.db` + 独立 trace 目录，qwen-plus，`mvn test` 70/70。

| # | 标准 | 结果 | 证据 |
|---|---|---|---|
| A1 | 同 `sessionId` 记得上文 | ✅ | 两轮后 `messages(review-s1)=4`；第二轮答"你叫小明，你在准备 Agent 开发实习的岗位" |
| A2 | 窗口裁剪 | ✅ | 11 轮（22 条）→ 表里只留 20 条，最早的 id 被淘汰（133..152） |
| A3 | 偏好写入（带来源/置信度） | ✅ | `semantic` conf 0.8 + `episodic` conf 0.6，`source=conversation:review-s1`，`dim=1024`，episodic `expires_at` 90 天后 |
| A4 | 跨会话召回 | ✅ | 新 session 召回 3 条（semantic 0.674 + 2 条 episodic），答"你之前在准备 Agent 开发实习的面试"；trace 含 `recalled/score/cosine` |
| A5 | 闲聊/临时数据不写 | ✅ | 3 条瞬时任务前后 `memories` 4→4；一次性会话 `written=0` |
| A6 | 过期记忆不召回 | ⚠️ **部分** | 过期行本身未进 `recalled`；但同会话的 episodic 摘要仍带同一事实，答案仍给出邮箱（见下） |
| A7 | 不退化 | ✅ | `mvn test` 70/70；`run_eval --limit 5` 5/5 OK |
| A8 | 可查看/删除/重建 | ✅ | DB 可查；`MemoryStore.forget` 有单测；删 `memory.db` 重启后表重建、0 行 |

### A6 的问题：遗忘不彻底

复现：

1. 任务"记住：我的邮箱是 m6-review@example.com" → 同时写入 `semantic`（原句）与 `episodic`（摘要里含任务原文）；
2. 把 `semantic.expires_at` 改到 2000 年；
3. 问"我的邮箱是什么？" → 召回里**没有**那条 semantic，但 episodic 仍命中并带出邮箱。

根因：**同一事实存了两份**（semantic 原句 + episodic 摘要里的任务原文），只过期一处不够。

可选修法（2026-11-08 讨论；**2026-11-10 已选 b 并实施**，见下）：

| 方案 | 做法 | 代价 |
|---|---|---|
| a | 同一会话已写 semantic 就不再写 episodic | 简单；失去会话摘要 |
| b | episodic 不再回填任务原文，只写"做了什么 + 结论" | 摘要信息量降低，但**避免 PII 二次落库**（推荐） |
| c | 删除/过期时按 `source` 级联清理派生记忆 | 语义上合理，但"过期"自动级联容易误删其他会话摘要 |
| d | 召回时按内容去重 + 过滤已过期来源 | 实现复杂，治标 |

> 结论：A1~A5、A7、A8 通过；A6 记为**部分通过**，遗留项进 `STATUS.md` 的 backlog。

### 修复记录（2026-11-10，方案 b 已实施）

- **改动**：`MemoryExtractor.extractEpisodic()` 不再回填**任务原文**与**答复原文**，摘要固定为
  `会话摘要｜工具：<工具名 / 无>｜步骤：<N>｜结论：完成`——只留"做了什么 + 结论"，事实归 semantic 管。
- **为什么连答复也不回填**：复核复现里邮箱在**任务和答复里各出现一次**
  （`任务：记住：我的邮箱是 …｜结果：已记录你的邮箱：…`），只去掉任务原文仍会从"结果"漏出去。
- **实测（独立库 + 独立 trace）**：写入"记住：我的邮箱是 fix-verify@example.com" → 把 semantic 的
  `expires_at` 改到 2000 年 → 新会话问"我的邮箱是什么？"：
  - 召回只剩 1 条 `episodic`（`会话摘要｜工具：terminate｜步骤：1｜结论：完成`），
    `semantic` 被 `findActive` 过滤掉；
  - 答案"我无法访问或查询你的邮箱地址…"，**不再出现邮箱**；
  - `select count(*) from memories where kind='episodic' and text like '%fix-verify@example.com%'` → **0**。
- **回归测试**：`MemoryForgettingTest`（把上面三步固化）、
  `MemoryExtractorTest.episodicDoesNotEchoTaskOrAnswer`。
- **代价**：摘要不再带对话内容，跨会话召回主要靠 semantic（方案 b 已预期）。
- **测试**：`mvn test` **72/72**（70 + 2 个新增）。
- **未做**：P0.7 的主动清理——过期数据仍留在表里，只是不再被召回。

### P0.7 决策（2026-11-10）：逻辑删除，不做物理清理

**结论**：过期 = `expires_at < now`，召回时由 `findActive` 过滤即可；**不删数据、不加 `status` 列、不引入定时任务**。

理由：

- 功能上等价：过期数据永远不会进 `recalled`，也就不会进 prompt；
- 数据量可控：只有 episodic 有 TTL（90 天），单用户每天至多 1 条，增长极慢；
- 可回溯：排查"以前记过什么"时有原始数据可查，比物理删除更利于排障；
- 少一个调度线程，也少一套并发/失败处理。

代价与边界：

- 表会缓慢增长；`list()` 仍能看到过期项——将来若对外暴露"记忆管理"接口，返回一个**派生的 `expired` 字段**即可，不要加列；
- "过期"不等于"删除"：若将来有隐私合规诉求（用户要求清除），走显式 `forget`（硬删）或手动调用 `purgeExpired`，不做自动物理清理。

`purgeExpired()` **保留**：作为手动/管理用的硬清理入口（已有单测），不接调度。
