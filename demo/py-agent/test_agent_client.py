"""Python 侧自测：通过 Streamable HTTP 连接 agent-worker，调用 run_task / ping。

前提：先启动 agent.py（监听 8002 端口，且 Ollama 正在运行）。
"""

import asyncio

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

URL = "http://127.0.0.1:8002/mcp"


async def main() -> None:
    async with streamable_http_client(URL) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            tools = await session.list_tools()
            print("[1] tools:", [t.name for t in tools.tools])

            r = await session.call_tool("ping", {})
            print("[2] ping ->", r.content[0].text)

            r = await session.call_tool("run_task", {"task": "用一句话介绍你自己"})
            print("[3] run_task ->", r.content[0].text)

    print("Python agent (HTTP) test PASSED")


if __name__ == "__main__":
    asyncio.run(main())
