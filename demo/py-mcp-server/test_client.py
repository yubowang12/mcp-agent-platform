"""Python 侧自测：以 MCP Client 身份通过 Streamable HTTP 连接 demo-tools server。

前提：先启动 server.py（监听 8001 端口）。

运行（在 demo/.venv 中）：
    python test_client.py
"""

import asyncio
import sys
from pathlib import Path

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

SERVER = Path(__file__).parent / "server.py"
URL = "http://127.0.0.1:8001/mcp"


async def main() -> None:
    async with streamable_http_client(URL) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()

            tools = await session.list_tools()
            print("[1] tools:", [t.name for t in tools.tools])

            r = await session.call_tool("get_time", {})
            print("[2] get_time ->", r.content[0].text)

            r = await session.call_tool("add", {"a": 1.5, "b": 2.25})
            print("[3] add ->", r.content[0].text)

    print("Python MCP client (HTTP) test PASSED")


if __name__ == "__main__":
    asyncio.run(main())
