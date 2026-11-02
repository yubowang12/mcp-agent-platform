# 基于 MCP 协议的微服务化多智能体协作平台（毕业设计工作区）

> 技术栈：**Java (Spring AI + Spring Boot) 编排层** + **Python (MCP SDK) Agent/工具微服务** + **Vue 控制台（规划中）** + **DashScope `qwen-plus`（OpenAI 兼容接口；2026-11-02 起替代本地 Ollama）**
>
> 架构基线：**规划型 Host（方案 B）**——Java 编排层自带规划 LLM，负责规划/调度/聚合；Python Agent 作为执行器。详见 `docs/architecture.md`。

## 当前进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| 阶段 0 | 技术验证（MCP 协议打通） | ✅ 已完成（2026-10-19） |
| M1 | 依赖与装配：Ollama starter、toolcallback、工具名前缀 bean | ✅ 已完成（2026-10-22） |
| M2 | `/api/task` 单步闭环（手动 ReAct 循环 + 超时 + terminate 工具） | ✅ 已完成（2026-10-22，测试跑通） |
| M3 | 多步编排 + trace 落盘（JSONL） | ✅ 已完成（2026-10-25，2026-10-28 回归通过） |
| M4 | Agent 能力工具集（summarize / translate）+ 工具选择验证 | ✅ 已完成（2026-10-28，5/5 命中，`mvn test` 4/4） |
| M5 | 熔断 / 降级 / 故障注入 | ✅ 已完成（2026-11-02，熔断三态 + 快速失败 + DEGRADED，`mvn test` 24/24） |
| M6 | 30 条任务集批量评测 | ✅ 已完成（2026-11-02，严格成功 25/30、宽松 27/30；直通组对照；见 `demo/eval/RESULTS.md`） |

## 目录结构

```
my_work/
├── demo/                              # 技术验证与 M1~M4 实现
│   ├── py-mcp-server/                 # 工具微服务（MCP Server，8001）
│   │   ├── server.py                  #   get_time / add / subtract
│   │   └── test_client.py             #   Python 侧自测
│   ├── py-agent/                      # Agent 微服务（MCP Server，8002）
│   │   ├── agent.py                   #   summarize / translate / run_task / ping（调用 DashScope）
│   │   └── test_agent_client.py       #   Agent 自测
│   ├── java-mcp-client/               # Java 编排层（Spring AI MCP Client，8090）
│   │   ├── src/main/java/com/example/mcpdemo/
│   │   │   ├── agent/                 # 编排器（M2~M5）
│   │   │   │   ├── TaskOrchestrator.java      # 无状态 ReAct 循环
│   │   │   │   ├── TaskContext.java           # 每请求上下文（状态隔离）
│   │   │   │   ├── TimeoutToolCallback.java   # 工具超时 + 结果记录
│   │   │   │   ├── CircuitBreakingToolCallback.java  # 熔断包装（M5）
│   │   │   │   ├── ToolCallRecorder.java      # 成败/耗时旁路记录（M5）
│   │   │   │   ├── TerminateTool.java         # 终止工具
│   │   │   │   ├── model/                     # AgentState/TaskStatus/TaskTrace/TraceStep
│   │   │   │   └── trace/TraceRecorder.java   # trace JSONL 落盘
│   │   │   ├── resilience/            # 熔断（M5）：CircuitBreaker / Registry / Properties
│   │   │   ├── config/                # McpToolPrefixConfig / PlannerProperties
│   │   │   └── web/                   # Catalog/Planner/Task 接口
│   │   └── src/test/java/com/example/mcpdemo/  # 单测 21 + 集成 3
│   ├── eval/                          # M6 评测（tasks.json / run_eval.py / results / RESULTS.md）
│   └── .venv/                         # Python 虚拟环境（mcp 2.3.0 + openai）
├── docs/
│   ├── architecture.md                # 编排层架构基线（规划型 Host，M1~M6）
│   ├── project-design-document.md     # 项目设计文档（需求/架构/规划）
│   ├── tech-validation-report.md      # 阶段 0 技术验证报告
│   ├── development-protocol.md        # 开发协作协议（五步循环 + 进度）
│   └── learning-plan.md               # 学习计划与验收记录
├── maven-settings.xml                 # Maven 配置（本地仓库重定向到工作区）
└── README.md
```

## 接口一览（Java 编排层，端口 8090）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/servers` | 已连接的 MCP Server |
| GET | `/api/catalog` | 前缀化工具目录（模型视角） |
| GET | `/api/tools` | 原始工具列表 |
| GET | `/api/planner/ping` | 规划模型连通性检查 |
| POST | `/api/task` | **主入口**：`{"task":"...","maxSteps":10}` → 返回 trace |
| POST | `/api/agent/run` | 阶段 0 直通接口（对照组 A） |

## 快速开始

```powershell
# 0. 一次性：把 DashScope Key 填进 local profile（该文件已被 .gitignore 排除）
#    demo/java-mcp-client/src/main/resources/application-local.yml
#      spring:
#        ai:
#          openai:
#            api-key: sk-...

# 1. 一键启动（Python 8001/8002 + Java 8090，脚本自动激活 local profile）
.\demo\start-dev.ps1

# 2. 验证
curl http://localhost:8090/api/catalog
$body = '{"task":"现在几点？","maxSteps":10}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri 'http://localhost:8090/api/task' -Method Post -Body $bytes -ContentType 'application/json; charset=utf-8'
```

> **不要**把 Key 写进 `application.yml`：那是受 git 跟踪的文件，提交后 Key 会留在历史里。当前约定：`application.yml` 只写 `${DASHSCOPE_API_KEY:}` 占位符，真实 Key 放 `application-local.yml`（gitignored），用 `local` profile 覆盖。
>
> **Python 执行器也读同一个文件**：`agent.py` 取 Key 的顺序是 `LLM_API_KEY` → `DASHSCOPE_API_KEY` → `application-local.yml` 里的 `spring.ai.openai.api-key`，所以 Key 只写一份，直接 `python demo/py-agent/agent.py` 也能跑（不需要先设环境变量）。
>
> 手动启动：`$env:SPRING_PROFILES_ACTIVE='local'`，再分别起两个 Python 服务与 `mvn spring-boot:run`；或用 `.\demo\start-dev.ps1` 一次起完。
>
> **base-url 有个坑**：Java 侧 Spring AI 会自己拼 `/v1/chat/completions`，所以 `spring.ai.openai.base-url` 配到 `.../compatible-mode` 就够（**不要带 `/v1`**，否则 404）；Python 的 `openai` SDK 相反，`LLM_BASE_URL` 要带 `/v1`。换 DeepSeek 时 Java 配 `https://api.deepseek.com`、Python 配 `https://api.deepseek.com/v1`。
>
> 模型可换：`mcp.planner.model`（Java 侧）与 `LLM_MODEL`（Python 执行器）都支持 `qwen-plus` / `qwen-turbo` / `qwen-max`，换 DeepSeek 用 `deepseek-chat`（`deepseek-reasoner` 不支持 function calling）。
>
> 跑测试：`mvn test` 的 3 个集成用例需要 8001/8002 在线且能拿到 Key；测试类已加 `@ActiveProfiles("local")`，Key 从 `application-local.yml` 读。

trace 落盘位置：`demo/logs/trace/yyyy-MM-dd.jsonl`（JSONL，一行一条）。

## 测试

```powershell
# 纯单元测试（不依赖 8001/8002、不调用模型 API）
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml -Dtest="CircuitBreakerTest,CircuitBreakingToolCallbackTest,TimeoutToolCallbackTest,TaskDegradationTest,NoToolsAvailableTest" test

# 全量（3 个集成用例需要先启动 8001/8002，并配好 DashScope Key / local profile）
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml test
```

2026-11-02 实测：全量 **24/24** 通过（21 个纯单测 + 3 个集成用例），trace 见 `demo/logs/trace/`。其中：
`CircuitBreakerTest`（8）验状态机；`CircuitBreakingToolCallbackTest`（5）验熔断包装器；`TimeoutToolCallbackTest`（3）验失败判定；`TaskDegradationTest`（4）验 `DEGRADED` 规则；`NoToolsAvailableTest`（1）无工具场景；`TaskOrchestratorTest`（3）集成回归。

## 文档索引

| 文档 | 内容 |
|---|---|
| `docs/architecture.md` | 架构基线：规划型 Host、工具目录/命名空间/规划循环/trace/降级、M1~M6 里程碑、实验设计 |
| `docs/project-design-document.md` | 需求分析、总体架构、核心设计、接口/数据模型、风险与创新点 |
| `docs/development-protocol.md` | 开发协作协议（五步循环、约定、进度记录） |
| `docs/learning-plan.md` | 学习单元与验收记录（U1~U7） |
| `docs/tech-validation-report.md` | 阶段 0 技术验证（协议打通 + 踩坑记录） |

## 后续路线

- ~~**M4**：Agent 能力工具集（`summarize` / `translate`，description 写明"不做什么"）~~ ✅ 2026-10-28 完成
- ~~**M5**：熔断/降级 + 故障注入实验~~ ✅ 2026-11-02 完成（恢复需重启编排层，重连留 R1）
- ~~**M6**：30 条任务集批量评测脚本（成功率/延迟/工具选择准确率）~~ ✅ 2026-11-02 完成（`demo/eval/RESULTS.md`）
- **R1/R2/R3**：注册中心、前端控制台、安全沙箱（扩展项）
