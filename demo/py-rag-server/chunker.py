"""两阶段分块：短段落直接成块，超长段落交 LLM 切分。

阶段一（规则）：按空行切段落，`## 标题` 段落作为小节标记（不单独成块，
其后的块继承该标题）；段落 ≤ CHUNK_MAX_CHARS 直接成块。
阶段二（LLM）：只有超长段落才调用 qwen-plus，要求输出严格 JSON 数组
`[{title, text, start}]`；解析失败或空数组时整段成块并 warn，不重试。

直接运行本文件即可重建 chunks.json：
    python demo/py-rag-server/chunker.py
"""

import json
import logging
import re
from pathlib import Path

import config

log = logging.getLogger("chunker")

# front-matter：文件开头的 --- ... --- 块
FRONT_MATTER_RE = re.compile(r"\A---\s*\n(.*?)\n---\s*\n?", re.S)
# Markdown 标题（1~6 级）
HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*$")
# 段落分隔：一个或多个空行
PARAGRAPH_RE = re.compile(r"\n\s*\n")

LLM_SYSTEM_PROMPT = (
    "你是文本分块助手。把用户给出的一节长文本切成若干语义完整的小块，"
    "供后续向量检索使用。必须遵守：\n"
    "1) 只输出 JSON 数组，不要输出解释、前后缀或 Markdown 代码围栏；\n"
    "2) 数组元素形如 {\"title\": \"简短小标题\", \"text\": \"原文片段\", \"start\": 0}；\n"
    "3) 每块 150~500 个汉字，按原文顺序切分，块之间不要重叠；\n"
    "4) 不要改写原文：text 必须是原文的连续片段，标点与措辞保持原样；\n"
    "5) 不要遗漏原文内容，也不要把同一段文字重复放进两个块；\n"
    "6) start 可以给近似字符偏移，宿主会用 text 在原文中重新定位。"
)


# ---------------------------------------------------------------------------
# 第一阶段：front-matter 与段落
# ---------------------------------------------------------------------------

def parse_front_matter(raw: str) -> tuple[dict, str]:
    """解析 front-matter，返回 (元数据, 去掉 front-matter 后的正文)。

    缺失或格式不合法时返回 ({}, 原文)，由调用方回退到文件名。
    """
    match = FRONT_MATTER_RE.match(raw)
    if not match:
        return {}, raw
    meta: dict[str, str] = {}
    for line in match.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        meta[key.strip()] = value.strip().strip('"').strip("'")
    return meta, raw[match.end():]


def split_paragraphs(body: str) -> list[str]:
    """按空行切段落，返回去除首尾空白的段落列表。

    偏移不在这里返回：段落文本是正文的连续片段，调用方用 str.find
    从上一段末尾向后定位即可（见 build_chunks）。
    """
    return [part.strip() for part in PARAGRAPH_RE.split(body) if part.strip()]


def _heading_of(paragraph: str) -> str | None:
    """若段落整段就是一个 Markdown 标题，返回标题文本，否则返回 None。"""
    lines = paragraph.splitlines()
    if len(lines) != 1:
        return None
    match = HEADING_RE.match(lines[0])
    return match.group(1).strip() if match else None


# ---------------------------------------------------------------------------
# 第二阶段：超长段落交 LLM
# ---------------------------------------------------------------------------

def _parse_json_array(content: str) -> list | None:
    """从模型输出里截取 JSON 数组并解析；失败返回 None。"""
    text = content.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    start, end = text.find("["), text.rfind("]")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, list) else None


def chunk_long_paragraph(text: str, title: str) -> list[dict]:
    """把一段超长文本切成若干块；返回 [{title, text, start}]，start 为段内偏移。

    任何失败（网络、非 JSON、空数组、全部文本对不上）都回退为整段一块，
    只记 warning，不重试——避免一次坏输出阻断整个 ingest。
    """
    try:
        response = config.client().chat.completions.create(
            model=config.LLM_MODEL,
            temperature=0,
            messages=[
                {"role": "system", "content": LLM_SYSTEM_PROMPT},
                {"role": "user", "content": f"小节标题：{title}\n\n原文：\n{text}"},
            ],
        )
        blocks = _parse_json_array(response.choices[0].message.content or "")
    except Exception as exc:  # noqa: BLE001 - 分块失败不应阻断 ingest
        log.warning("LLM 分块调用失败（%s: %s），整段成块：%s",
                    type(exc).__name__, exc, title)
        return [{"title": title, "text": text, "start": 0}]

    if not blocks:
        log.warning("LLM 分块返回空或非 JSON，整段成块：%s", title)
        return [{"title": title, "text": text, "start": 0}]

    result: list[dict] = []
    for item in blocks:
        if not isinstance(item, dict):
            continue
        piece = str(item.get("text", "")).strip()
        if not piece:
            continue
        offset = text.find(piece)
        result.append({
            "title": str(item.get("title") or title).strip() or title,
            "text": piece,
            # 找不到就退回段落起点（交接单允许模型给近似值）
            "start": offset if offset >= 0 else 0,
        })

    if not result:
        log.warning("LLM 分块结果无可用的 text 字段，整段成块：%s", title)
        return [{"title": title, "text": text, "start": 0}]

    found = sum(1 for block in result if block["start"] > 0 or text.startswith(block["text"]))
    if found == 0:
        log.warning("LLM 分块文本与原文完全对不上，整段成块：%s", title)
        return [{"title": title, "text": text, "start": 0}]
    if found < len(result):
        log.warning("LLM 分块有 %d/%d 块未能在原文中定位，按段落起点处理：%s",
                    len(result) - found, len(result), title)
    return result


# ---------------------------------------------------------------------------
# 组装 chunks.json
# ---------------------------------------------------------------------------

def build_chunks(corpus_dir: Path = config.CORPUS_DIR) -> dict:
    """扫描语料目录，返回 docs/06-rag-v1.md §6.2 约定的 dict。"""
    docs: list[dict] = []
    chunks: list[dict] = []
    long_handled = 0

    for path in sorted(corpus_dir.glob("*.md")):
        raw = path.read_text(encoding="utf-8")
        meta, body = parse_front_matter(raw)
        if not meta:
            log.warning("缺少 front-matter，用文件名回退：%s", path.name)
        doc_id = meta.get("id") or path.stem
        doc_title = meta.get("title") or path.stem
        docs.append({
            "id": doc_id,
            "title": doc_title,
            "source": meta.get("source", ""),
            "date": meta.get("date", ""),
        })

        section_title = doc_title
        cursor = 0
        index = 0
        for paragraph in split_paragraphs(body):
            # 段落是正文的连续片段：从上一段末尾向后找起点
            start = body.find(paragraph, cursor)
            if start < 0:
                start = cursor
            cursor = start + len(paragraph)

            heading = _heading_of(paragraph)
            if heading:
                section_title = heading
                continue

            if len(paragraph) > config.CHUNK_MAX_CHARS:
                long_handled += 1
                pieces = chunk_long_paragraph(paragraph, section_title)
            else:
                pieces = [{"title": section_title, "text": paragraph, "start": 0}]

            for piece in pieces:
                chunks.append({
                    "id": f"{doc_id}-{index:03d}",
                    "docId": doc_id,
                    "title": piece["title"],
                    "text": piece["text"],
                    "start": start + int(piece.get("start", 0)),
                })
                index += 1

    return {
        "version": 1,
        "embeddingModel": config.EMBED_MODEL,
        "dim": config.EMBED_DIM,
        "docs": docs,
        "chunks": chunks,
    }


def write_chunks(data: dict, path: Path = config.CHUNKS_JSON) -> Path:
    """把分块结果写成 UTF-8 JSON（保留中文，便于人工 review 与 diff）。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")
    return path


def main() -> None:
    logging.basicConfig(level=logging.WARNING,
                        format="%(levelname)s %(name)s: %(message)s")
    data = build_chunks()
    write_chunks(data)
    chunks = data["chunks"]
    avg_len = sum(len(c["text"]) for c in chunks) / len(chunks) if chunks else 0
    print(f"docs={len(data['docs'])} chunks={len(chunks)} "
          f"avg_len={avg_len:.1f} file={config.CHUNKS_JSON}")


if __name__ == "__main__":
    main()
