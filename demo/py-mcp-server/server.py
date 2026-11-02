"""工具微服务示例：一个通过 MCP 协议暴露工具能力的 Python MCP Server。

启动方式（Streamable HTTP，独立进程监听 8001）：
    python server.py

暴露的工具：
    - get_time: 返回服务器当前时间
    - add:      两数相加
    - subtract: 两数相减
"""

from datetime import datetime

from mcp.server.mcpserver import MCPServer

mcp = MCPServer("demo-tools")


@mcp.tool()
def get_time() -> str:
    """返回服务器当前时间（ISO 8601 格式）。"""
    return datetime.now().isoformat(timespec="seconds")


@mcp.tool()
def add(a: float, b: float) -> float:
    """计算两个数字之和。

    Args:
        a: 第一个数字
        b: 第二个数字
    """
    return a + b

@mcp.tool()
def subtract(a: float, b: float) -> float:
    """计算两个数字之差

    Args:
        a: 第一个数字
        b: 第二个数字
    """
    return a - b


if __name__ == "__main__":
    # 作为独立微服务运行：streamable-http 传输，监听 8001 端口
    mcp.run(transport="streamable-http", port=8001)
