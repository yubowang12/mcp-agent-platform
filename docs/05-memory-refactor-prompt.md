# 记忆层拆包重构：执行 Prompt

> **用法**：把本文件全文粘给执行会话；或直接说"读 `docs/05-memory-refactor-prompt.md` 并照做"。
> 目标：**只改包结构，不改任何行为**。完成后测试必须仍是 70/70。

---

## 0. 前置检查

- 仓库根：`E:\dsh_working\my_work`，模块：`demo/java-mcp-client`（Java 17 / Spring Boot 3.5.16 / Spring AI 1.1.8）
- 基线提交：`dae6bba feat(memory): memory layer v1 ...`（实现已完成、70/70 通过）
- 先跑 `git status` 确认工作区干净；不干净就先别动，问清楚。

## 1. 目标目录（B 方案：3 个业务包 + infra + config）

```
com/example/mcpdemo/memory/
├── AgentMemory.java                 ← 门面，留在根包
├── config/
│   ├── MemoryConfig.java
│   └── MemoryProperties.java
├── shortterm/                       ← 短期：会话消息持久化
│   └── SqliteChatMemoryRepository.java
├── longterm/                        ← 长期：存储 / 抽取 / 召回 / 提示 / 向量
│   ├── MemoryStore.java
│   ├── SqliteMemoryStore.java
│   ├── MemoryExtractor.java
│   ├── MemoryRecallService.java
│   ├── MemoryPromptBuilder.java
│   ├── EmbeddingService.java
│   └── MemoryVectors.java
├── model/                           ← 数据结构（record / enum）
│   ├── MemoryKind.java
│   ├── MemoryRecord.java
│   ├── RecalledMemory.java
│   ├── MemoryCandidate.java
│   └── SessionSummary.java
└── infra/                           ← 基础设施（两个 Repository 共用）
    └── SqliteMemoryDb.java
```

## 2. 迁移表（17 个文件，`git mv` 保留历史）

| 现路径（`.../mcpdemo/memory/`） | 新路径 |
|---|---|
| `AgentMemory.java` | 不动（根包） |
| `config/MemoryConfig.java` | 不动 |
| `config/MemoryProperties.java` | 不动 |
| `SqliteChatMemoryRepository.java` | `shortterm/SqliteChatMemoryRepository.java` |
| `MemoryStore.java` | `longterm/MemoryStore.java` |
| `SqliteMemoryStore.java` | `longterm/SqliteMemoryStore.java` |
| `MemoryExtractor.java` | `longterm/MemoryExtractor.java` |
| `MemoryRecallService.java` | `longterm/MemoryRecallService.java` |
| `MemoryPromptBuilder.java` | `longterm/MemoryPromptBuilder.java` |
| `EmbeddingService.java` | `longterm/EmbeddingService.java` |
| `MemoryVectors.java` | `longterm/MemoryVectors.java` |
| `MemoryKind.java` | `model/MemoryKind.java` |
| `MemoryRecord.java` | `model/MemoryRecord.java` |
| `RecalledMemory.java` | `model/RecalledMemory.java` |
| `MemoryCandidate.java` | `model/MemoryCandidate.java` |
| `SessionSummary.java` | `model/SessionSummary.java` |
| `SqliteMemoryDb.java` | `infra/SqliteMemoryDb.java` |

包名相应变为：`com.example.mcpdemo.memory.shortterm` / `.longterm` / `.model` / `.infra`。

**测试文件跟着挪**（包名必须与对应实现类一致）：

| 现路径（`src/test/.../mcpdemo/memory/`） | 新路径 |
|---|---|
| `SqliteChatMemoryRepositoryTest.java` | `shortterm/` |
| `MemoryExtractorTest.java` | `longterm/` |
| `MemoryRecallServiceTest.java` | `longterm/` |
| `MemoryPromptBuilderTest.java` | `longterm/` |
| `MemoryVectorsTest.java` | `longterm/` |
| `SqliteMemoryStoreTest.java` | `longterm/` |

> ⚠️ `MemoryRecallService.decay(...)` 是**包级私有**（供测试直接调），所以测试必须与实现同包，不能图省事全塞到 `memory` 根包。

## 3. 包外引用（必须同步改 import）

| 文件 | 引用 |
|---|---|
| `agent/TaskContext.java` | `RecalledMemory`、`MemoryRecord`（→ `.model`） |
| `agent/TaskOrchestrator.java` | `AgentMemory`（根包不变）、`RecalledMemory`、`SessionSummary`（→ `.model`） |
| `test/agent/MemoryTraceMappingTest.java` | 同上（3 处 → `.model`） |
| `memory/config/MemoryConfig.java` | `SqliteChatMemoryRepository`（→ `.shortterm`） |

包内互相引用（`AgentMemory` 引用 `longterm/model/config`、`longterm` 引用 `model/infra` 等）跟着改。

## 4. 硬性约束

1. **不改类名、方法签名、字段名、构造器参数**；不改 SQL、配置项（`mcp.memory.*`）、日志文案。
2. **不加接口、不拆实体、不改表结构**——这次只挪包。
3. 依赖方向：`shortterm` 与 `longterm` **互不依赖**；都只依赖 `model` / `infra` / `config`；`AgentMemory` 是唯一同时用到两边的类。
4. 不要动 `memory` 之外的包（第 3 节的 import 除外）。
5. 不 `rebase` / 不 `squash`；完成后**新建一个 commit**。

## 5. 执行步骤

```powershell
# 1) 确认基线
git status            # 应为空
git log -1 --oneline  # 应为 dae6bba

# 2) 按第 2 节逐条 git mv，并改 package 声明
#    （目录不存在时先 New-Item -ItemType Directory）

# 3) 改第 3 节与包内 import

# 4) 编译
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml -DskipTests compile

# 5) 全量测试（期望 70/70）
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml test

# 6) 残留检查：不应再出现旧路径
Get-ChildItem demo\java-mcp-client\src -Recurse -Filter *.java |
  Select-String 'com\.example\.mcpdemo\.memory\.(MemoryStore|MemoryRecord|MemoryKind|MemoryCandidate|RecalledMemory|SessionSummary|MemoryExtractor|MemoryRecallService|MemoryPromptBuilder|EmbeddingService|MemoryVectors|SqliteMemoryStore|SqliteChatMemoryRepository|SqliteMemoryDb)'

# 7) 提交
git add -A
git commit -m "refactor(memory): split memory package into shortterm/longterm/model/infra"
```

## 6. 验收

- [ ] `mvn test` 仍是 **70/70**（数量不能少；若因测试包位置变动出现编译错，改 import，不改逻辑）
- [ ] `git show --stat HEAD` 里**只有** `.java` 的 rename（+ 少量 import 行），没有业务逻辑 diff
- [ ] 第 5 步的残留检查返回空
- [ ] 可选：`.\demo\start-dev.ps1` 起服务，打一条任务，确认记忆功能仍可用

## 7. 文档（执行完顺手更新）

- `docs/04-memory-layer-v1.md`：2.1.1 的目录树（已写好目标结构，确认与代码一致即可）；
- `docs/03-code-walkthrough.md`：搜 `mcpdemo.memory` 的旧路径，改包名；
- `docs/STATUS.md`：日志加一条（日期 / 做了什么 / 测试结果），状态里的"扁平包待拆"改成"已拆分"。

## 8. 回报格式（给评审用）

```
1) 移动：17 个类 + 6 个测试，目标包：shortterm/longterm/model/infra
2) 测试：mvn test → 70/70（耗时 x s）
3) 残留引用检查：0
4) 意外：无 / 或说明（例如某个类不适合移动及原因）
5) 提交：<commit hash> refactor(memory): ...
```
