"""混合检索：字符 bigram BM25 + 向量余弦 + RRF 融合 + 按文档去重。

全部是纯函数（可单测），只有 `search()` 会读模块级索引；索引由
`load_index()` 在服务启动时构建一次，常驻内存。

自测：python demo/py-rag-server/retrieval.py --selftest
"""

import argparse
import logging
import math
import re
import sys
from collections import Counter

import config
import store

try:  # 与 store.py 相同的降级策略
    import numpy as np
except ImportError:  # pragma: no cover - 取决于运行环境
    np = None

log = logging.getLogger("retrieval")

# 连续的中文 / 字母 / 数字片段；其它字符（标点、空白）都当分隔符
RUN_RE = re.compile(r"[a-z0-9\u4e00-\u9fff]+")


class IndexMissing(RuntimeError):
    """索引尚未构建（rag.db 不存在或未加载）。"""


# ---------------------------------------------------------------------------
# 纯函数：分词与打分
# ---------------------------------------------------------------------------

def tokenize(text: str) -> list[str]:
    """小写 → 只保留中文/字母/数字 → 连续片段切 bigram，单字保留 unigram。"""
    lowered = (text or "").lower()
    tokens: list[str] = []
    for run in RUN_RE.findall(lowered):
        if len(run) == 1:
            tokens.append(run)
        else:
            tokens.extend(run[i:i + 2] for i in range(len(run) - 1))
    return tokens


def bm25_scores(chunk_tokens: list[list[str]], query_tokens: list[str],
                k1: float = config.BM25_K1, b: float = config.BM25_B) -> list[float]:
    """标准 BM25（idf 用 ln(1 + (N-df+0.5)/(df+0.5))），返回每块的分数。"""
    n = len(chunk_tokens)
    if n == 0 or not query_tokens:
        return []
    df: Counter = Counter()
    for tokens in chunk_tokens:
        df.update(set(tokens))
    avgdl = sum(len(tokens) for tokens in chunk_tokens) / n
    if avgdl <= 0:
        return [0.0] * n

    terms = list(dict.fromkeys(query_tokens))  # 去重且保持顺序
    scores: list[float] = []
    for tokens in chunk_tokens:
        tf = Counter(tokens)
        dl = len(tokens)
        score = 0.0
        for term in terms:
            freq = tf.get(term, 0)
            if not freq:
                continue
            idf = math.log(1 + (n - df[term] + 0.5) / (df[term] + 0.5))
            score += idf * freq * (k1 + 1) / (freq + k1 * (1 - b + b * dl / avgdl))
        scores.append(score)
    return scores


def cosine_scores(matrix, query_vector: list[float]) -> list[float]:
    """矩阵行已 L2 归一化；这里只需对 query 归一化后做内积。"""
    if query_vector is None or len(matrix) == 0:
        return []
    norm = math.sqrt(sum(value * value for value in query_vector)) or 1.0

    if np is not None and isinstance(matrix, np.ndarray):
        if len(query_vector) != matrix.shape[1]:
            return [0.0] * len(matrix)
        return (matrix @ np.asarray(query_vector, dtype=np.float32) / norm).tolist()

    width = len(matrix[0])
    if len(query_vector) != width:
        return [0.0] * len(matrix)
    scores: list[float] = []
    for row in matrix:
        scores.append(sum(a * b for a, b in zip(row, query_vector)) / norm)
    return scores


def rrf_fuse(rank_lists: list[list[str]], k: int = config.RRF_K) -> dict[str, float]:
    """Reciprocal Rank Fusion：fused[cid] += 1 / (k + rank + 1)，rank 从 0 开始。"""
    fused: dict[str, float] = {}
    for rank_list in rank_lists:
        for rank, chunk_id in enumerate(rank_list):
            fused[chunk_id] = fused.get(chunk_id, 0.0) + 1.0 / (k + rank + 1)
    return fused


def dedupe_by_doc(scored: dict[str, float], chunks_by_id: dict[str, dict]) -> list[dict]:
    """同一文档只保留 fused 最高的块；结果按分数降序（同分按 chunkId）。"""
    ordered = sorted(scored.items(), key=lambda item: (-item[1], item[0]))
    best: dict[str, dict] = {}
    for chunk_id, score in ordered:
        chunk = chunks_by_id.get(chunk_id)
        if chunk is None:
            continue
        doc_id = chunk["docId"]
        if doc_id in best:
            continue
        best[doc_id] = {**chunk, "score": score}
    return sorted(best.values(), key=lambda chunk: (-chunk["score"], chunk["id"]))


def _top_ids(scores: list[float], ids: list[str], limit: int) -> list[str]:
    """按分数降序取前 limit 个正分块；同分按 id 保证可复现。"""
    ranked = sorted(range(len(scores)), key=lambda i: (-scores[i], ids[i]))
    return [ids[i] for i in ranked[:limit] if scores[i] > 0]


# ---------------------------------------------------------------------------
# 索引：启动时全量加载
# ---------------------------------------------------------------------------

class Index:
    """内存索引：归一化向量矩阵 + 块元数据 + BM25 预分词。"""

    def __init__(self, matrix, chunks: list[dict]) -> None:
        self.matrix = matrix
        self.chunks = chunks
        self.by_id = {chunk["id"]: chunk for chunk in chunks}
        # BM25 的"文档"= 小节标题 + 正文：标题是块的主要语义（如"转载段落""任职要求"），
        # 只索引正文会让"按标题找块"这类查询全部落空。
        self.tokens = [tokenize(f'{chunk["title"]}\n{chunk["text"]}') for chunk in chunks]
        self.avgdl = (sum(len(tokens) for tokens in self.tokens) / len(self.tokens)
                      if self.tokens else 0.0)


_INDEX: Index | None = None


def build_index(matrix, chunks: list[dict]) -> Index:
    return Index(matrix, chunks)


def set_index(index: Index | None) -> None:
    global _INDEX
    _INDEX = index


def load_index(db_path=config.DB_PATH) -> Index | None:
    """db 不存在返回 None（服务不崩，工具报 index_missing）。"""
    from pathlib import Path

    path = Path(db_path)
    if not path.exists():
        log.warning("索引库不存在：%s", path)
        return None
    db = store.init_db(path)
    try:
        matrix, chunks = store.load_matrix(db)
    finally:
        db.close()
    return build_index(matrix, chunks)


def search(query: str, top_k: int = config.TOP_K, *,
           index: Index | None = None,
           query_vector: list[float] | None = None) -> list[dict]:
    """混合检索入口：向量 top30 + BM25 top30 → RRF → 按文档去重 → top_k。"""
    idx = index if index is not None else _INDEX
    if idx is None:
        raise IndexMissing("请先运行 ingest.py 构建索引")
    text = (query or "").strip()
    if not text or not idx.chunks:
        return []

    ids = [chunk["id"] for chunk in idx.chunks]
    rank_lists: list[list[str]] = []

    if query_vector is not None:
        rank_lists.append(_top_ids(cosine_scores(idx.matrix, query_vector), ids,
                                   config.VEC_CAND))
    else:
        log.warning("未提供 query_vector，本次仅走 BM25")

    bm_scores = bm25_scores(idx.tokens, tokenize(text))
    rank_lists.append(_top_ids(bm_scores, ids, config.BM_CAND))

    fused = rrf_fuse(rank_lists)
    results = dedupe_by_doc(fused, idx.by_id)
    return results[:max(0, top_k)]


# ---------------------------------------------------------------------------
# 自测：覆盖分词、RRF 单调性、同文档去重、空结果
# ---------------------------------------------------------------------------

def _selftest() -> None:
    # 1) 分词：中文 bigram、英文 bigram、单字 unigram
    tokens = tokenize("Agent开发，AI")
    assert "ag" in tokens and "en" in tokens, tokens
    assert "开发" in tokens, tokens
    assert "ai" in tokens and "a" not in tokens, tokens
    assert tokenize("") == []
    print("[1/4] bigram 分词 ok")

    # 2) RRF：同一列表内名次越靠前分越高；两路一致时分数更高
    one = rrf_fuse([["a", "b", "c"]])
    assert one["a"] > one["b"] > one["c"], one
    both = rrf_fuse([["a", "b"], ["a", "b"]])
    assert both["a"] > one["a"], both
    assert rrf_fuse([]) == {}
    print("[2/4] RRF 单调性 ok")

    # 3) 同 docId 只留最高分
    chunks = {"x": {"id": "x", "docId": "d1", "title": "t"},
              "y": {"id": "y", "docId": "d1", "title": "t"},
              "z": {"id": "z", "docId": "d2", "title": "t"}}
    deduped = dedupe_by_doc({"x": 0.1, "y": 0.5, "z": 0.2}, chunks)
    assert [c["id"] for c in deduped] == ["y", "z"], deduped
    print("[3/4] 同文档去重 ok")

    # 4) 空结果：空索引、空 query 都返回 []
    empty = build_index([], [])
    assert search("任意问题", index=empty) == []
    assert search("   ", index=empty) == []
    print("[4/4] 空结果 ok")

    print("selftest ok: 4/4")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="检索自测 / 手工查询")
    parser.add_argument("--selftest", action="store_true", help="跑 4 个断言")
    parser.add_argument("--query", help="对已构建的索引做一次检索")
    parser.add_argument("--top-k", type=int, default=config.TOP_K)
    args = parser.parse_args(argv)

    if args.selftest:
        _selftest()
        return 0
    if args.query:
        import ingest  # 延迟导入，避免 selftest 依赖 openai

        index = load_index()
        if index is None:
            print("index_missing: 请先运行 ingest.py", file=sys.stderr)
            return 1
        vector = ingest.embed_texts([args.query])[0]
        for item in search(args.query, args.top_k, index=index, query_vector=vector):
            print(f'{item["score"]:.4f} {item["id"]} [{item["title"]}] {item["text"][:40]}')
        return 0
    parser.print_help()
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING,
                        format="%(levelname)s %(name)s: %(message)s")
    raise SystemExit(main())
