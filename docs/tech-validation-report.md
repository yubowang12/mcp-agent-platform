# 阶段 0 技术验证报告：MCP 协议打通

> 项目：基于 MCP 协议的微服务化多智能体协作平台设计与实现
> 日期：2026-10-19
> 状态：✅ 全部验证通过

## 一、验证目标与结论

| # | 验证项 | 结论 | 证据 |
|---|--------|------|------|
| 1 | Python MCP Server ↔ Python MCP Client 互通 | ✅ 通过 | `test_client.py`：tools 列表、get_time、add 均正常 |
| 2 | Java (Spring AI) MCP Client ↔ Python MCP Server 互通 | ✅ 通过 | `/api/servers`、`/api/tools`、`/api/demo/*` 正常返回 |
| 3 | Python Agent ↔ Ollama 本地 LLM 推理 | ✅ 通过 | `/api/agent/run` 返回中文推理结果 |
| 4 | 全链路：Java HTTP → MCP → Python Agent → Ollama | ✅ 通过 | 中文任务经 Java 下发，Agent 调用 qwen2.5:3b 推理并回传 |

## 二、最终架构（验证版）

```
浏览器/curl ──HTTP──▶ Java 编排层 (Spring Boot 3.5.16, 端口 8090, Spring AI 1.1.8)
                          │  MCP Streamable HTTP
          ┌───────────────┴────────────────┐
          ▼                                ▼
  demo-tools (Python, 8001)        agent-worker (Python, 8002)
  MCP Server: get_time / add       MCP Server: run_task / ping
                                          │ OpenAI 兼容接口
                                          ▼
                                  Ollama qwen2.5:3b (127.0.0.1:11434)
```

## 三、技术选型明细

| 组件 | 选型 | 版本 | 说明 |
|------|------|------|------|
| Java 编排层 | Spring Boot | 3.5.16 | 与 Spring AI 1.1.x 配套的 LTS 版本 |
| MCP 客户端 | Spring AI MCP Client Starter | 1.1.8 | 自动装配 `List<McpSyncClient>` |
| MCP 协议 SD| MCP Java SDK | 0.18.3 | 由 Spring AI 1.1.8 BOM 管理 |
| Python 侧 | mcp SDK | 2.3.0 | `mcp.server.mcpserver.MCPServer`（2.x 将 FastMCP 改名） |
| 本地 LLM | Ollama | 0.40.6 | 已装 qwen2.5:7b/3b、gemma3:4b、llama3.2:3b 等 |
| 传输方式 | Streamable HTTP | MCP 规范 | 服务以独立进程监听端口，跨语言、可跨主机 |

## 四、关键踩坑与解决记录（对后续开发有参考价值）

1. **Python 3.14 + miniconda 下 `python -m venv` 的 ensurepip 失败**
   - 现象：`PermissionError` 写临时目录；根因是 Windows 上 `os.mkdir(path, 0o700)`（tempfile.mkdtemp 的内部实现）创建的目录当前用户不可写。
   - 解决：在 venv 的 `site-packages/sitecustomize.py` 中把 Windows 下 0o700 放宽为 0o755，pip/ensurepip 恢复正常。
2. **清华 PyPI 镜像对 pip 请求返回 403**
   - 现象：`https://pypi.tuna.tsinghua.edu.cn/simple/mcp/` 对 pip 的 UA 返回 403，urllib 却能访问。
   - 解决：本次安装显式使用官方源 `-i https://pypi.org/simple`。
3. **MCP Python SDK 2.x 不兼容 1.x 写法**
   - `from mcp.server.fastmcp import FastMCP` 在 2.3.0 中会报错提示改名。
   - 解决：`from mcp.server.mcpserver import MCPServer`，`@mcp.tool()` 与 `mcp.run(transport=...)` 用法不变。
4. **Windows 沙箱禁止命名管道，stdio 传输不可用**
   - 现象：asyncio 子进程管道（CreateFile → WinError 5）与 Java ProcessBuilder 的 stdio 传输均被沙箱拦截。
   - 解决：改用 Streamable HTTP 传输——这恰好也是未来多机 Agent 集群的正确形态（Agent 作为网络微服务）。
   - 说明：在正常开发机（无沙箱）上 stdio 亦可使用，二者 MCP 协议完全一致，只是传输层不同。

## 五、可复现的运行步骤

```powershell
# 1. 启动 Ollama（若未运行）
ollama serve

# 2. 启动两个 Python 微服务（两个终端）
demo\.venv\Scripts\python.exe demo\py-mcp-server\server.py   # 8001
demo\.venv\Scripts\python.exe demo\py-agent\agent.py         # 8002

# 3. 启动 Java 编排层
mvn -s maven-settings.xml -f demo/java-mcp-client/pom.xml spring-boot:run  # 8090

# 4. 验证
curl http://localhost:8090/api/servers
curl http://localhost:8090/api/tools
curl http://localhost:8090/api/demo/get-time
curl "http://localhost:8090/api/demo/add?a=1.5&b=2.5"
curl -X POST http://localhost:8090/api/agent/run -H "Content-Type: application/json" -d '{"task":"用一句话介绍你自己"}'
```

## 六、对项目的启示

1. **协议链路已实证**：Java 编排层通过 MCP 标准协议调用 Python Agent 完全可行，异构架构成立，项目的"方案可行性"有了实打实的证据。
2. **传输方式要写进设计**：生产形态用 Streamable HTTP（Agent 微服务化、可跨主机），本地调试可保留 stdio，二者统一为 MCP 协议。
3. **工程化基础设施要提前准备**：注册中心（8001/8002 端口硬编码的问题，下一步改为服务注册发现）、健康检查、超时熔断，这是调度与治理层（技术亮点）的落点。
4. **本地模型够用**：qwen2.5:3b/7b 可用，评测实验（吞吐/延迟/成功率对比）可在本机完成，不依赖付费 API。
