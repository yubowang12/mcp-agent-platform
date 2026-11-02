"""Agent 微服务示例：一个以 MCP Server 形式暴露的 Python 智能体。

架构说明：
    - 每个 Agent 是集群中的一个微服务，通过 MCP 协议暴露**能力工具集**。
    - Java 编排层作为 MCP Client 连接本 Agent，规划模型按工具 description 选择能力。
    - Agent 内部调用云端大模型（默认 DashScope 的 OpenAI 兼容接口）完成推理。

能力工具集（M4）：
    summarize  把长文本压缩为要点（不做翻译 / 不做查询）
    translate  中英等语言互译（不做摘要 / 不做润色）
    run_task   通用兜底（仅在无专用工具匹配时使用）
    ping       健康检查

启动方式（Streamable HTTP，独立进程监听 8002）：
    python agent.py

环境变量：
    LLM_BASE_URL   OpenAI 兼容接口地址，默认 https://dashscope.aliyuncs.com/compatible-mode/v1
    LLM_API_KEY    API Key；未设置时回退读 DASHSCOPE_API_KEY，再回退读 Java 侧的
                   application-local.yml（一份 Key 同时给 Java 和 Python 用）
    LLM_MODEL      模型名，默认 qwen-plus；可换 qwen-turbo / qwen-max / deepseek-chat
"""

import os
import re
from pathlib import Path

from mcp.server.mcpserver import MCPServer
from openai import OpenAI

LLM_BASE_URL = os.environ.get(
    "LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen-plus")

# Java 侧的 local profile：Key 只写这一份，Python 直接读同一个文件
LOCAL_YML = (Path(__file__).resolve().parents[1]
             / "java-mcp-client" / "src" / "main" / "resources" / "application-local.yml")


def resolve_api_key() -> str:
    """按优先级取 Key：环境变量 → application-local.yml。"""
    from_env = os.environ.get("LLM_API_KEY") or os.environ.get("DASHSCOPE_API_KEY")
    if from_env:
        return from_env
    if LOCAL_YML.exists():
        # 只认 spring.ai.openai.api-key 这一种写法，避免引入 YAML 依赖
        for line in LOCAL_YML.read_text(encoding="utf-8").splitlines():
            match = re.match(r"^\s*api-key:\s*(\S+)", line)
            if match:
                value = match.group(1).strip().strip('"').strip("'")
                if value and not value.startswith("${"):
                    return value
    return ""


LLM_API_KEY = resolve_api_key()

# 执行器温度固定 0.3（与实验基线一致，见 docs/architecture.md 第八节"可复现"）
TEMPERATURE = 0.3

if not LLM_API_KEY:
    raise SystemExit(
        "未配置 API Key。三种方式任选其一：\n"
        "  1) 在 %s 里写 spring.ai.openai.api-key（推荐，Java 也读它）\n"
        "  2) $env:DASHSCOPE_API_KEY = \"sk-...\"\n"
        "  3) 启动时设置 LLM_API_KEY" % LOCAL_YML
    )

client = OpenAI(base_url=LLM_BASE_URL, api_key=LLM_API_KEY)

mcp = MCPServer("agent-worker")

SYSTEM_PROMPT = "你是 Agent 集群中的一个执行型智能体，负责简洁高效地完成用户下发的任务。"

# 各能力的系统提示：只做本职工作，避免模型在工具内"顺手"越界
SUMMARIZER_SYSTEM_PROMPT = (
    "你是文本摘要助手。你只做压缩与提炼：保留关键信息、去掉冗余，"
    "不回答问题、不扩写、不评论。"
    "输出必须使用与原文相同的语言：原文是中文就输出中文，原文是英文就输出英文，绝不翻译。"
    "最终答复只给结果，不要复述输入。"
)
TRANSLATOR_SYSTEM_PROMPT = (
    "你是翻译助手。你只输出译文：忠实于原文的意思和语气，"
    "不解释、不评论、不摘要、不加译注。"
)

# ---------------------------------------------------------------------------
# 工具描述（M4 核心产物）
#
# 说明：Spring AI / MCP 把 description 原样交给规划模型，模型只看得到
#       name + description + inputSchema。description 里"不做什么"比"做什么"
#       更能防错选，因此每个能力工具都显式写出越界场景与正确的替代工具。
#       用常量而不是 docstring，是为了避免 __doc__ 的缩进污染发给模型的文本。
# ---------------------------------------------------------------------------

SUMMARIZE_DESCRIPTION = """把一段文本压缩成要点/摘要。仅用于「摘要、概括、提炼、压缩」类需求。

输入：
- text：需要压缩的原文（一段完整文本）
- max_words：输出长度上限，默认 100；中文按字计、英文按词计

输出：紧凑的要点或摘要，只包含原文已有的信息，语言与原文一致（不翻译）。

不做什么（以下需求不要调用本工具）：
- 不翻译文本（翻译请用 translate）
- 不做润色、改写风格、续写、扩写
- 不查询资料、不计算、不联网、不核实事实真伪
- 不处理图片、URL、文件，只处理传入的文本"""

TRANSLATE_DESCRIPTION = """把一段文本翻译成目标语言。仅用于「翻译、译成、用英文/中文说」类需求。

输入：
- text：待翻译的原文（一段完整文本）
- target_lang：目标语言，可写语言名或语言代码，如 英文 / English / en、中文 / Chinese / zh

输出：仅译文，保持原意与语气，不加解释、不加译注。

不做什么（以下需求不要调用本工具）：
- 不做摘要、压缩、提炼（摘要请用 summarize）
- 不做润色、改写、续写、模仿风格
- 不做事实核查，不回答原文之外的问题
- 不处理图片、URL、文件，只处理传入的文本"""

RUN_TASK_DESCRIPTION = """通用兜底：用一条自然语言任务让执行智能体推理作答。

规划器自己能完成的写作、解释、建议类任务，直接用最终答复回答，不要调用本工具

只在前面的专用能力工具（摘要、翻译）以及时间、算术等专用工具都不匹配时，才使用本工具。

不做什么：
- 摘要/压缩/提炼 → 用 summarize
- 翻译 → 用 translate
- 查询当前时间、加减乘除等已有专用工具的能力 → 优先用对应专用工具
- 不用于闲聊占位、不代替用户做决定"""


def _ask_llm(system_prompt: str, user_prompt: str) -> str:
    """单轮调用执行模型（基础版：无重试、无规则兜底）。"""
    response = client.chat.completions.create(
        model=LLM_MODEL,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        temperature=TEMPERATURE,
    )
    return response.choices[0].message.content or ""


@mcp.tool(title="文本摘要", description=SUMMARIZE_DESCRIPTION)
def summarize(text: str, max_words: int = 100) -> str:
    """把长文本压缩为要点（基础版：prompt 约束长度，不做截断等规则兜底）。"""
    user_prompt = (
        f"请把下面的文本压缩成要点，总长度不超过 {max_words} 个字（英文按词计）。"
        "只保留关键信息，不要添加原文没有的内容。"
        "输出语言必须与原文一致（中文原文用中文输出），不要翻译。"
        "文本较长时用 3~5 条要点，较短时用一句话概括；直接给结果，不要开场白。\n\n"
        f"文本：\n{text}"
    )
    return _ask_llm(SUMMARIZER_SYSTEM_PROMPT, user_prompt)


@mcp.tool(title="文本翻译", description=TRANSLATE_DESCRIPTION)
def translate(text: str, target_lang: str) -> str:
    """把文本翻译成目标语言（基础版：单轮翻译，不做术语表/规则校验）。"""
    user_prompt = (
        f"请把下面的文本翻译成{target_lang}。保持原意与语气，"
        "只输出译文，不要加解释、译注或原文。\n\n"
        f"文本：\n{text}"
    )
    return _ask_llm(TRANSLATOR_SYSTEM_PROMPT, user_prompt)


@mcp.tool(title="通用任务（兜底）", description=RUN_TASK_DESCRIPTION)
def run_task(task: str) -> str:
    """通用兜底入口：没有专用工具匹配时，用自然语言让执行模型作答。

    Args:
        task: 自然语言任务描述，例如 "用一句话介绍你自己"
    """
    return _ask_llm(SYSTEM_PROMPT, task)


@mcp.tool(title="健康检查")
def ping() -> str:
    """健康检查工具：Agent 存活时返回 pong。"""
    return "pong"


if __name__ == "__main__":
    # 作为独立微服务运行：streamable-http 传输，监听 8002 端口
    mcp.run(transport="streamable-http", port=8002)
