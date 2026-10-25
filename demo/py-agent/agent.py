"""Agent 微服务示例：一个以 MCP Server 形式暴露的 Python 智能体。

架构说明：
    - 每个 Agent 是集群中的一个微服务，通过 MCP 协议暴露能力（run_task）。
    - Java 编排层作为 MCP Client 连接本 Agent，下发自然语言任务。
    - Agent 内部调用本地 Ollama 大模型完成推理（可替换为任意 LLM API）。

启动方式（stdio 传输，由 MCP 客户端拉起）：
    python agent.py

环境变量：
    LLM_BASE_URL   OpenAI 兼容接口地址，默认 http://127.0.0.1:11434/v1（Ollama）
    LLM_API_KEY    API Key，Ollama 可随便填
    LLM_MODEL      模型名，默认 qwen2.5:3b
"""

import os

from mcp.server.mcpserver import MCPServer
from openai import OpenAI

LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://127.0.0.1:11434/v1")
LLM_API_KEY = os.environ.get("LLM_API_KEY", "ollama")
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen2.5:3b")

client = OpenAI(base_url=LLM_BASE_URL, api_key=LLM_API_KEY)

mcp = MCPServer("agent-worker")

SYSTEM_PROMPT = "你是 Agent 集群中的一个执行型智能体，负责简洁高效地完成用户下发的任务。"


@mcp.tool()
def run_task(task: str) -> str:
    """执行一条自然语言任务，返回 LLM 的推理结果。

    该工具是 Java 编排层与 Python Agent 之间的标准交互入口。

    Args:
        task: 自然语言任务描述，例如 "用一句话介绍你自己"
    """
    response = client.chat.completions.create(
        model=LLM_MODEL,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": task},
        ],
        temperature=0.3,
    )
    return response.choices[0].message.content or ""


@mcp.tool()
def ping() -> str:
    """健康检查工具：Agent 存活时返回 pong。"""
    return "pong"


if __name__ == "__main__":
    # 作为独立微服务运行：streamable-http 传输，监听 8002 端口
    mcp.run(transport="streamable-http", port=8002)
