from typing import Callable, Any, get_type_hints
from inspect import signature, Parameter
from pydantic import create_model
from pydantic.json_schema import GenerateJsonSchema


class ToolRegistry:
    def __init__(self):
        # key: tool name, value: {func, schema}
        self._tools: dict[str, dict[str, Any]] = {}

    def register(self, name: str | None = None) -> Callable[[Callable], Callable]:
        """装饰器：注册工具函数，自动生成参数JSON Schema"""
        def decorator(func: Callable) -> Callable:
            tool_name = name if name is not None else func.__name__
            # 拿到函数的参数类型和返回值类型
            hints = get_type_hints(func)
            # 拿到函数的参数列表和是否有默认值
            sig = signature(func)

            # 构造 pydantic 模型字段 (不熟悉 api 直接 AI 生成)
            fields: dict[str, tuple[type, Any]] = {}
            for param in sig.parameters.values():
                param_name = param.name
                param_type = hints.get(param_name, Any)
                default = ... if param.default is Parameter.empty else param.default
                fields[param_name] = (param_type, default)

            # 动态创建 pydantic model，生成 schema
            ArgsModel = create_model(f"{tool_name}_Args", **fields)
            schema = ArgsModel.model_json_schema(schema_generator=GenerateJsonSchema)

            self._tools[tool_name] = {
                "function": func,
                "input_schema": schema,
                "doc": func.__doc__ or ""
            }
            return func
        return decorator

    def list_tools(self) -> dict[str, dict[str, Any]]:
        return self._tools

    def call_tool(self, tool_name: str, **kwargs) -> Any:
        """简易调用工具，模拟MCP call"""
        if tool_name not in self._tools:
            raise KeyError(f"Tool {tool_name} not found")
        return self._tools[tool_name]["function"](**kwargs)


# ========== 实例化注册表 ==========
registry = ToolRegistry()

# ========== 注册2个示例工具 ==========
@registry.register(name="add")   # 真正的工具调用还需要 description 等等参数
def add(a: int, b: int = 1) -> int:
    """两个整数相加"""
    return a + b


@registry.register(name="greet")
def greet(name: str, greeting: str = "Hello") -> str:
    """问候，支持自定义问候语"""
    return f"{greeting}, {name}!"


def main():
    print("===== 迷你 MCP Server - 已注册工具列表 =====")
    tools = registry.list_tools()
    for tool_name, meta in tools.items():
        print(f"\n【Tool Name】: {tool_name}")
        print(f"【Doc】: {meta['doc']}")
        print(f"【Input JSON Schema】:")
        import json
        print(json.dumps(meta["input_schema"], ensure_ascii=False, indent=2))

    # 简单测试调用
    print("\n===== 测试工具调用 =====")
    print("add(3,5) =", registry.call_tool("add", a=3, b=5))
    print("greet(name='Alice') =", registry.call_tool("greet", name="Alice"))
    print("greet(name='Bob', greeting='Hi') =", registry.call_tool("greet", name="Bob", greeting="Hi"))


if __name__ == "__main__":
    main()
