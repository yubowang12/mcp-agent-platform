"""SQLite 索引读写：建表、幂等重建、启动时全量加载。

表结构与 docs/06-rag-v1.md §6.3 一致；向量用 float32 小端 BLOB 存储，
编码方式与 Java 侧 MemoryVectors 相同（struct.pack("<{dim}f", ...)）。
"""

import logging
import math
import sqlite3
import struct
from pathlib import Path

import config

try:  # numpy 只是加速项：cp314 装不上时自动退回纯 Python
    import numpy as np
except ImportError:  # pragma: no cover - 取决于运行环境
    np = None

log = logging.getLogger("store")

DDL = """
CREATE TABLE IF NOT EXISTS docs(
  id TEXT PRIMARY KEY, title TEXT NOT NULL, source TEXT, date TEXT);

CREATE TABLE IF NOT EXISTS chunks(
  id TEXT PRIMARY KEY,
  doc_id TEXT NOT NULL REFERENCES docs(id),
  title TEXT,
  text TEXT NOT NULL,
  start INTEGER,
  embedding BLOB,
  dim INTEGER);

CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(doc_id);
"""


def init_db(path: Path = config.DB_PATH) -> sqlite3.Connection:
    """打开（必要时创建）数据库并执行 DDL；WAL 提升并发读性能。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(DDL)
    conn.commit()
    return conn


def encode_vector(vector: list[float]) -> bytes:
    """float32 小端编码，与 Java MemoryVectors.toBlob 保持一致。"""
    return struct.pack(f"<{len(vector)}f", *vector)


def decode_vector(blob: bytes | None, dim: int | None) -> list[float] | None:
    """解码 BLOB；长度不符或缺失时返回 None（由调用方按零向量处理）。"""
    if not blob or not dim or len(blob) != 4 * dim:
        return None
    return list(struct.unpack(f"<{dim}f", blob))


def rebuild(db: sqlite3.Connection, docs: list[dict], chunks: list[dict]) -> None:
    """先清空再写入，保证同一输入重复 ingest 结果一致（R8）。"""
    with db:  # 单个事务：要么整体成功，要么回滚
        db.execute("DELETE FROM chunks")
        db.execute("DELETE FROM docs")
        db.executemany(
            "INSERT INTO docs(id, title, source, date) VALUES(?, ?, ?, ?)",
            [(d["id"], d["title"], d.get("source", ""), d.get("date", "")) for d in docs],
        )
        rows = []
        for chunk in chunks:
            embedding = chunk.get("embedding")
            if isinstance(embedding, (list, tuple)):
                embedding = encode_vector(list(embedding))
            rows.append((chunk["id"], chunk["docId"], chunk.get("title", ""),
                         chunk["text"], chunk.get("start", 0), embedding,
                         chunk.get("dim")))
        db.executemany(
            "INSERT INTO chunks(id, doc_id, title, text, start, embedding, dim) "
            "VALUES(?, ?, ?, ?, ?, ?, ?)", rows)


def load_chunks(db: sqlite3.Connection) -> list[dict]:
    """读出全部块（含 embedding bytes 与所属文档的 source/date）。"""
    rows = db.execute(
        "SELECT c.id, c.doc_id, c.title, c.text, c.start, c.embedding, c.dim, "
        "       d.title AS doc_title, d.source, d.date "
        "FROM chunks c JOIN docs d ON d.id = c.doc_id "
        "ORDER BY c.id"
    ).fetchall()
    return [{
        "id": row[0],
        "docId": row[1],
        "title": row[2],
        "text": row[3],
        "start": row[4],
        "embedding": row[5],
        "dim": row[6],
        "docTitle": row[7],
        "source": row[8],
        "date": row[9],
    } for row in rows]


def load_matrix(db: sqlite3.Connection) -> tuple[object, list[dict]]:
    """返回 (N×dim 已 L2 归一化的矩阵, 块列表)，行与块一一对应。

    缺向量或维度不符的块补零向量：保留 BM25 可检索性，向量路自然跳过。
    numpy 可用时返回 np.ndarray(float32)，否则返回 list[list[float]]。
    """
    chunks = load_chunks(db)
    dim = config.EMBED_DIM
    vectors: list[list[float]] = []
    missing = 0
    for chunk in chunks:
        vector = decode_vector(chunk.get("embedding"), chunk.get("dim"))
        if vector is None or len(vector) != dim:
            missing += 1
            vectors.append([0.0] * dim)
        else:
            vectors.append(vector)
    if missing:
        log.warning("有 %d/%d 个块缺少可用向量，按零向量处理", missing, len(chunks))

    if np is not None and vectors:
        matrix = np.asarray(vectors, dtype=np.float32)
        norms = np.linalg.norm(matrix, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return matrix / norms, chunks

    normalized: list[list[float]] = []
    for vector in vectors:
        norm = math.sqrt(sum(value * value for value in vector)) or 1.0
        normalized.append([value / norm for value in vector])
    return normalized, chunks
