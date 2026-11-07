# 记忆层 v1 规格（交接用）

> 状态：**规格已冻结（2026-11-07），待实施**。实施在另一个会话进行，完成后回来评审并更新 `STATUS.md`。
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
