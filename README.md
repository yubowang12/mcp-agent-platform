# 基于 MCP 协议的微服务化多智能体协作平台（毕业设计工作区）

> 技术栈：**Java (Spring AI + Spring Boot) 编排层** + **Python (MCP SDK) Agent/工具微服务** + **Vue 控制台（规划中）** + **Ollama 本地大模型**
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
| M6 | 30 条任务集批量评测 | ⬜ 未开始 |

## 目录结构

```
my_work/
├── demo/                              # 技术验证与 M1~M4 实现
│   ├── py-mcp-server/                 # 工具微服务（MCP Server，8001）
│   │   ├── server.py                  #   get_time / add / subtract
│   │   └── test_client.py             #   Python 侧自测
│   ├── py-agent/                      # Agent 微服务（MCP Server，8002）
│   │   ├── agent.py                   #   summarize / translate / run_task / ping（调用 Ollama）
│   │   └── test_agent_client.py       #   Agent 自测
│   ├── java-mcp-client/               # Java 编排层（Spring AI MCP Client，8090）
│   │   ├── src/main/java/com/example/mcpdemo/
│   │   │   ├── agent/                 # 编排器（M2~M4）
│   │   │   │   ├── TaskOrchestrator.java      # 无状态 ReAct 循环
│   │   │   │   ├── TaskContext.java           # 每请求上下文（状态隔离）
│   │   │   │   ├── TimeoutToolCallback.java   # 工具超时 + 耗时采样
│   │   │   │   ├── TerminateTool.java         # 终止工具
│   │   │   │   ├── model/                     # AgentState/TaskStatus/TaskTrace/TraceStep
│   │   │   │   └── trace/TraceRecorder.java   # trace JSONL 落盘
│   │   │   ├── config/                # McpToolPrefixConfig / PlannerProperties
│   │   │   └── web/                   # Catalog/Planner/Task 接口
│   │   └── src/test/java/com/example/mcpdemo/agent/  # 集成测试 + NoToolsAvailableTest
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
# 1. 启动 Ollama（若未运行）
ollama serve

# 2. 启动两个 Python 微服务（只起一份！端口 8001/8002）
demo\.venv\Scripts\python.exe demo\py-mcp-server\server.py
demo\.venv\Scripts\python.exe demo\py-agent\agent.py

# 3. 启动 Java 编排层（监听 8090）
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml spring-boot:run

# 4. 验证
curl http://localhost:8090/api/catalog
$body = '{"task":"现在几点？","maxSteps":10}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri 'http://localhost:8090/api/task' -Method Post -Body $bytes -ContentType 'application/json; charset=utf-8'
```

trace 落盘位置：`demo/logs/trace/yyyy-MM-dd.jsonl`（JSONL，一行一条）。

## 测试

```powershell
# 纯单元测试（不依赖 8001/8002/Ollama）
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml -Dtest=NoToolsAvailableTest test

# 全量（3 个集成用例需要先启动 8001/8002 与 Ollama）
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
- **M6**：30 条任务集批量评测脚本（成功率/延迟/工具选择准确率）
- **R1/R2/R3**：注册中心、前端控制台、安全沙箱（扩展项）
