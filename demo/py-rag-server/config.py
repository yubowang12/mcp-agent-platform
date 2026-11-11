"""RAG v1 集中配置：路径、模型、分块与检索参数。

所有魔法数都收敛在这里，其它模块只 import 本文件，便于调参与复现。
密钥解析顺序与 demo/py-agent/agent.py 保持一致：环境变量 → application-local.yml。
"""

import os
import re
from pathlib import Path

# ---------------------------------------------------------------------------
# 路径（全部相对本文件定位，避免依赖启动时的工作目录）
# ---------------------------------------------------------------------------
ROOT = Path(__file__).resolve().parent
CORPUS_DIR = ROOT.parent / "rag" / "corpus"      # 10 份样例语料
CHUNKS_JSON = ROOT.parent / "rag" / "chunks.json"  # 分块产物（入库）
DB_PATH = ROOT.parent / "rag" / "rag.db"           # 向量索引（gitignore）

# ---------------------------------------------------------------------------
# 模型参数
# ---------------------------------------------------------------------------
EMBED_MODEL = "text-embedding-v3"
EMBED_DIM = 1024          # 与 Java 侧 spring.ai.openai.embedding.options.dimensions 一致
EMBED_BATCH = 10          # DashScope 单次 embeddings 请求的条数上限以内
LLM_MODEL = "qwen-plus"   # 只用于超长段落分块

# ---------------------------------------------------------------------------
# 分块 / 检索参数（见 docs/06-rag-v1.md §6.3、§6.4）
# ---------------------------------------------------------------------------
CHUNK_MAX_CHARS = 600     # 段落超过该长度才交 LLM 分块
VEC_CAND = 30             # 向量路召回候选数
BM_CAND = 30              # BM25 路召回候选数
RRF_K = 60                # RRF 融合常数
TOP_K = 5                 # 默认返回条数
BM25_K1 = 1.5             # BM25 词频饱和参数
BM25_B = 0.75             # BM25 长度归一化参数

# ---------------------------------------------------------------------------
# 模型服务地址与密钥
# Python 侧 base_url 必须带 /v1（Java 侧不带，见交接单 §1）
# ---------------------------------------------------------------------------
LLM_BASE_URL = os.environ.get(
    "LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

LOCAL_YML = (ROOT.parent / "java-mcp-client" / "src" / "main" / "resources"
             / "application-local.yml")


def resolve_api_key() -> str:
    """按优先级取 Key：环境变量 → application-local.yml（只认 api-key: 一行）。"""
    from_env = os.environ.get("LLM_API_KEY") or os.environ.get("DASHSCOPE_API_KEY")
    if from_env:
        return from_env
    if LOCAL_YML.exists():
        for line in LOCAL_YML.read_text(encoding="utf-8").splitlines():
            match = re.match(r"^\s*api-key:\s*(\S+)", line)
            if match:
                value = match.group(1).strip().strip('"').strip("'")
                if value and not value.startswith("${"):
                    return value
    return ""


def require_api_key() -> str:
    """取 Key；缺失时直接退出并给出三种配置方式（与 agent.py 一致）。"""
    key = resolve_api_key()
    if not key:
        raise SystemExit(
            "未配置 API Key。三种方式任选其一：\n"
            "  1) 在 %s 里写 spring.ai.openai.api-key（推荐，Java 也读它）\n"
            "  2) $env:DASHSCOPE_API_KEY = \"sk-...\"\n"
            "  3) 启动时设置 LLM_API_KEY" % LOCAL_YML
        )
    return key


_client = None


def client():
    """懒加载 OpenAI 兼容客户端（ingest / chunker 共用同一个连接）。"""
    global _client
    if _client is None:
        from openai import OpenAI

        _client = OpenAI(base_url=LLM_BASE_URL, api_key=require_api_key())
    return _client
