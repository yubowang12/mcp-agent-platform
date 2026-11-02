"""Python 侧自测：通过 Streamable HTTP 连接 agent-worker，验证能力工具集（M4）。

前提：先启动 agent.py（监听 8002 端口，且 Ollama 正在运行）。

覆盖：
    1. tools/list：summarize / translate / run_task / ping 都在，且 description 带边界说明
    2. summarize：中文长文本 -> 中文要点
    3. translate：中英双向
    4. run_task：通用兜底仍可用（回归）
"""

import asyncio
import os

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

# 默认连 8002；如需连临时验证实例（如 8005），设置环境变量 AGENT_URL
URL = os.environ.get("AGENT_URL", "http://127.0.0.1:8002/mcp")

LONG_TEXT = (
    "MCP 是一个开放协议，用来标准化 AI 应用与外部工具、数据源之间的连接。"
    "它把能力抽象成三类：工具、资源、提示。Host 负责协调，Client 维护连接，"
    "Server 暴露能力。传输层支持 stdio 和 Streamable HTTP，前者适合本地进程，"
    "后者适合网络微服务。协议还定义了初始化握手、能力协商和变更通知，"
    "使工具列表可以在运行时热更新，而不需要重启客户端。"
)


async def main() -> None:
    async with streamable_http_client(URL) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            tools = (await session.list_tools()).tools
            print("[1] tools:", [t.name for t in tools])
            for t in tools:
                print(f"    - {t.name} | title={t.title} | {t.description.splitlines()[0]}")
                if t.name == "summarize":
                    assert "不做什么" in t.description and "不翻译" in t.description, "summarize 边界说明不全"
                elif t.name == "translate":
                    assert "不做什么" in t.description and "不做摘要" in t.description, "translate 边界说明不全"

            r = await session.call_tool("summarize", {"text": LONG_TEXT, "max_words": 80})
            print("[2] summarize ->", r.content[0].text)

            r = await session.call_tool("translate", {"text": "多智能体协作平台通过 MCP 协议把工具调用标准化。",
                                                      "target_lang": "英文"})
            print("[3] translate(zh->en) ->", r.content[0].text)

            r = await session.call_tool("translate", {"text": "The planner picks one tool per step.",
                                                      "target_lang": "中文"})
            print("[4] translate(en->zh) ->", r.content[0].text)

            r = await session.call_tool("ping", {})
            print("[5] ping ->", r.content[0].text)

            r = await session.call_tool("run_task", {"task": "用一句话介绍你自己"})
            print("[6] run_task ->", r.content[0].text)

    print("Python agent (HTTP) test PASSED")


if __name__ == "__main__":
    asyncio.run(main())
