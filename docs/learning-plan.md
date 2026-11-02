# 毕设学习计划

> 项目：基于 MCP 协议的微服务化多智能体协作平台设计与实现
> 原则：**每块代码之前先学习 → 自测 → 验收 → 再动手写**
> 学习方式：学习包（目标 + 材料 + 关键问题 + 练习）→ 你学完回来自测 → 我讲评验收 → 一起写码

## 进度总览

| # | 单元 | 内容 | 对应项目模块 | 状态 |
|---|------|------|--------------|------|
| U1 | MCP 协议全景 | Host/Client/Server、工具/资源/提示、生命周期、stdio vs HTTP | 一切的基础 | ✅ 已完成 |
| U2 | Python 进阶补充 | 类、装饰器、类型注解、asyncio 异步编程 | Python 微服务 | ✅ 已完成 |
| U3 | 读懂 mcp SDK 与 demo | 读源码 + 逐行理解 demo 微服务 + 重写练习 | 现有 demo | ✅ 已完成 |
| U4 | LLM Agent 基础 | LLM API、提示词、工具调用、Agent 循环（先写无框架最小 agent） | Python Agent 微服务 | ✅ 已完成（2026-10-27 补课验收） |
| U5 | 微服务治理概念 | 注册发现、网关、健康检查、熔断限流（概念 + 设计图） | 注册中心/网关/治理 | ✅ 已完成（2026-10-30 验收 8/10） |
| U6 | Spring Boot / Spring AI 精读 | starter、auto-config、配置绑定、`McpSyncClient` 装配 | Java 编排层 | ✅ 已完成（2026-10-27 补课验收） |
| U7 | 工程化与正式骨架 | Maven 多模块、配置分层、日志、测试 | 正式项目搭建 | ⬜ |

> 说明：U5 与 U2/U3/U4 无强依赖，可与它们并行或按需调整顺序。U1 是总纲，必须先学。
> L1 验收通过（2026-10-19）：5 个关键问题基本达标，时序图正确，并完成"停服务看报错"实验。
> 架构基线（2026-10-19）：编排层定为**规划型 Host**（方案 B），详见 `docs/architecture.md`；哑编排（方案 A）保留为实验对照组。
> L2 验收通过（2026-10-20）：手写 ToolRegistry（装饰器 + pydantic 动态 schema）与真实 SDK 机制对齐；asyncio 五问全对（8.5/10）。
> L3 验收通过（2026-10-20）：initialize 三步握手（自报→应答→通知+缓存）理解正确；工具名正则约束与 yml key 权威性基本到位；练习 A（subtract 工具）并入 M1 验证"Java 零改动发现新工具"。
> 补课安排（2026-10-25）：U4/U6 因 M1~M3 先行实现，需补一次学习验收（题目 + 笔记），避免答辩被问"这块学过吗"。
> U4 补课验收通过（2026-10-27）：消息角色 / tool_calls 结构 / ReAct 三步 / think-act 映射理解到位（8/10）；纠正"工具提升训练精度""LLM 不具备技能"等表述；重复调用检测列为 M5 优化点。
> U6 补课验收通过（2026-10-27）：starter / 条件注解 / 装配链 / Builder / toolcallback / @ConfigurationProperties 基本到位（8/10）；补充了"配置绑定→传输层→客户端→握手→bean→List 注入"的完整链路。
> M4 实践补记（2026-10-28）：M4 的核心技术是"工具描述工程"（写"不做什么"、参数默认值、边界反例、规划器优先策略），没有单独开学习单元；相关内容已在 `demo/py-agent/agent.py` 与 `TaskOrchestrator` 系统提示里落地。答辩前建议按 M4 验收记录补一次 15 分钟过目（OpenAI Function calling 指南 / MCP Tools 章节 / Anthropic "writing effective tools"）。
> U5 验收通过（2026-10-30，8/10）：三态迁移、连续失败 vs 滑动窗口的取舍、半开"灰度放量"、fail-fast 与降级启动的代价都答到位。纠正两处：① **"重试是熔断恢复的前提"错**——恢复由半开探测的真实请求决定，熔断打开时重试是反模式（打到已知坏服务，加重故障）；② **熔断粒度按"故障相关性"而非工具数量**——同一 server 的工具共享进程/网络/模型，一个挂了通常整台不健康。补充概念：慢调用比例（slow call rate，通常先于错误率恶化）、隔舱（bulkhead，本项目 `TimeoutToolCallback` 的独立线程池就是雏形）、半开要限制并发（避免惊群）。

## 工作流程（每个单元）

1. **学习包**：我给出学习目标、阅读材料（官方文档/源码位置）、3~5 个关键问题、小练习
2. **你学习**：读材料、动手练习，学完回答关键问题（答不上来就回去补）
3. **我验收**：讲评、纠正理解偏差——**验收不通过不进下一单元**
4. **写码**：对应模块的代码，你动手为主，我讲解 + 审查
5. **闭环**：每个代码模块完成后，我出 2~3 道验收题，能答上来才算完成

## 各单元详情

### U1 MCP 协议全景
- **目标**：理解 MCP 是什么、为什么存在；Host/Client/Server 三角色；三类能力（tools/resources/prompts）；连接生命周期；stdio 与 Streamable HTTP 传输的差异
- **材料**：MCP 官方规范（modelcontextprotocol.io）架构页与传输页；Python SDK README；Spring AI MCP Client 文档
- **关键问题**：
  1. MCP 中 Host、Client、Server 分别是什么？我们的项目里谁是 Host、Client、Server？
  2. 为什么需要 MCP 这样的统一协议，而不是每个 Agent 各写各的接口？
  3. stdio 和 Streamable HTTP 传输的区别？各自适合什么场景？我们为什么选 HTTP？
  4. Server 的三类能力分别是什么？demo 里用到了哪些？
  5. 一次工具调用的完整报文流程（initialize → tools/list → tools/call → result）？
- **练习**：打开两个 Python 微服务源码，对照文档指出每行代码对应协议里的什么概念；画出三层时序图

### U2 Python 进阶补充
- **目标**：看得懂并能写 demo 中出现的 Python 代码：class、装饰器 `@mcp.tool()`、类型注解、`async/await`、事件循环
- **材料**：Python 官方教程（类/函数/类型注解章节）、《Effective Python》相关章节（我挑）；asyncio 官方文档入门篇
- **关键问题**：
  1. 装饰器是什么？`@mcp.tool()` 装饰器做了什么？（提示：它把函数注册进一个字典）
  2. `async def` 函数和普通函数有什么区别？调用 `await` 时发生了什么？
  3. 为什么 MCP SDK 的 server/client 都是异步的？同步会不会更简单？
  4. 类型注解 `def add(a: float, b: float) -> float` 对工具调用有什么作用？
- **练习**：手写一个最小 asyncio 程序（并发跑两个 sleep）；能手写一个装饰器把函数注册进字典

### U3 读懂 mcp SDK 与 demo
- **目标**：逐行理解 demo 中两个 Python 微服务的代码；能说出 FastMCP/MCPServer 内部做了什么；跟踪一次 `tools/call` 请求从进来到返回的完整路径
- **材料**：`demo/py-mcp-server/server.py`、`demo/py-agent/agent.py`、mcp 2.3.0 源码（`mcp/server/mcpserver/`、`mcp/client/`）
- **关键问题**：
  1. `MCPServer("demo-tools")` 这个名字在哪被上报？影响什么？
  2. `@mcp.tool()` 装饰的函数参数/返回值如何转换成本协议的 JSON Schema？
  3. server 收到 `tools/call` 后，走哪条调用链最终执行到 `get_time`？
  4. Agent 的 `run_task` 与工具服务的 `get_time` 在协议层面有区别吗？
- **练习**：给 demo 加一个新工具 `subtract` 并跑通；在 server 里打印日志观察请求到达

### U4 LLM Agent 基础
- **目标**：理解 LLM API 调用、提示词角色、**工具调用（function calling）**、Agent 循环（观察→思考→行动）；手写一个无框架最小 Agent
- **材料**：openai SDK 文档（chat.completions、tools 参数）；Ollama 文档；我给的 ReAct 资料
- **关键问题**：
  1. system/user/assistant 三种消息角色各起什么作用？
  2. 什么是 Tool Calling？模型返回的 tool_calls 长什么样？
  3. 为什么 Agent 需要"循环"？一次 LLM 调用为什么不一定是最终答案？
  4. 我们的 `agent.py` 目前只是单轮 LLM 调用——它还算 Agent 吗？差了什么才能成为完整 Agent？
- **练习**：用 openai SDK 直连 Ollama，手写一个带 1 个工具（如 get_time）的 Agent 循环并跑通

### U5 微服务治理概念
- **目标**：理解服务拆分、注册中心、服务发现、网关、健康检查、熔断降级/限流；设计出本项目的治理架构图
- **材料**：微服务入门资料（我挑中文优质材料）；Spring Cloud 官方文档选读
- **关键问题**：
  1. 为什么 Agent 集群需要注册中心？没有会怎样？
  2. 网关在集群里承担什么职责？为什么所有调用要先过网关？
  3. 熔断、降级、限流分别在解决什么问题？三者关系？
  4. 如果某个 Agent 挂掉，集群应该怎么表现才算"优雅"？
- **练习**：画一张"Java 网关 + 注册中心 + 3 个 Agent + 2 个工具服务"的部署与调用设计图

### U6 Spring Boot / Spring AI 精读
- **目标**：理解 starter 机制、auto-configuration、配置绑定、`McpSyncClient` 的自动装配链条；能看懂并修改 `application.yml` 与 Controller
- **材料**：Spring Boot 官方文档（auto-configuration / starters）；Spring AI 1.1.x MCP Client 文档
- **关键问题**：
  1. spring-ai-starter-mcp-client 做了什么？`List<McpSyncClient>` 是谁注入的？
  2. `spring.ai.mcp.client.streamable-http.connections.*` 的配置如何变成客户端对象？
  3. auto-configuration 条件注解（@ConditionalOnMissingBean 等）在这里的作用？
  4. 为什么要请求超时设置？不设置会出什么问题？
- **练习**：在 DemoController 上新增一个聚合接口（同时返回两个 server 的 serverInfo），并解释配置加载链路

### U7 工程化与正式骨架
- **目标**：搭建正式项目多模块结构（编排层 / 公共模块 / Python 服务目录）、配置分层（dev/prod）、日志、接口测试
- **材料**：Maven 多模块文档、Spring Boot profiles、JUnit 5
- **练习**：把 demo 重构为规范的 pom 多模块结构并跑通
