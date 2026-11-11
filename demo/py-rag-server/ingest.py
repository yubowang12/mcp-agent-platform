"""全量入库：分块（可复用 chunks.json）→ embedding → 写 SQLite。

用法：
    python ingest.py              # chunks.json 存在则复用，否则先分块
    python ingest.py --rechunk    # 强制重新分块（仅调试用）
    python ingest.py --chunks-only  # 只生成 chunks.json，不调 embedding
"""

import argparse
import json
import logging
from pathlib import Path

import chunker
import config
import store

log = logging.getLogger("ingest")


def load_chunks_json(path: Path = config.CHUNKS_JSON) -> dict | None:
    """读取已存在的分块产物；不存在或损坏返回 None。"""
    if not Path(path).exists():
        return None
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        log.warning("chunks.json 读取失败（%s），重新分块", exc)
        return None
    if not isinstance(data, dict) or not data.get("chunks"):
        log.warning("chunks.json 结构不完整，重新分块")
        return None
    return data


def _embed_batch(batch: list[str]) -> list[list[float]] | None:
    """一批 embedding；失败重试一次，仍失败返回 None（调用方跳过该批）。"""
    for attempt in (1, 2):
        try:
            response = config.client().embeddings.create(
                model=config.EMBED_MODEL,
                input=batch,
                dimensions=config.EMBED_DIM,
                encoding_format="float",
            )
            data = sorted(response.data, key=lambda item: item.index)
            return [list(item.embedding) for item in data]
        except Exception as exc:  # noqa: BLE001 - 单批失败不应阻断整库
            log.warning("embedding 第 %d 次失败（%s: %s）",
                        attempt, type(exc).__name__, exc)
    return None


def embed_texts(texts: list[str]) -> list[list[float] | None]:
    """按 EMBED_BATCH 分批向量化；失败的批次对应位置为 None。"""
    vectors: list[list[float] | None] = []
    for start in range(0, len(texts), config.EMBED_BATCH):
        batch = texts[start:start + config.EMBED_BATCH]
        got = _embed_batch(batch)
        if got is None:
            log.warning("跳过第 %d~%d 条（共 %d 条）", start, start + len(batch) - 1,
                        len(batch))
            vectors.extend([None] * len(batch))
        else:
            vectors.extend(got)
    return vectors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="RAG v1 入库")
    parser.add_argument("--rechunk", action="store_true", help="强制重新分块（调试用）")
    parser.add_argument("--chunks-only", action="store_true", help="只生成 chunks.json")
    parser.add_argument("--db", default=str(config.DB_PATH))
    parser.add_argument("--chunks-json", default=str(config.CHUNKS_JSON))
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.WARNING,
                        format="%(levelname)s %(name)s: %(message)s")

    json_path = Path(args.chunks_json)
    if args.rechunk:
        data = None
    else:
        data = load_chunks_json(json_path)
        if data is not None:
            print(f"reuse {json_path}")

    if data is None:
        data = chunker.build_chunks()
        chunker.write_chunks(data, json_path)
        avg_len = (sum(len(c["text"]) for c in data["chunks"]) / len(data["chunks"])
                   if data["chunks"] else 0)
        print(f'docs={len(data["docs"])} chunks={len(data["chunks"])} '
              f'avg_len={avg_len:.1f} file={json_path}')

    if args.chunks_only:
        return 0

    chunks = data["chunks"]
    vectors = embed_texts([chunk["text"] for chunk in chunks])

    dim = config.EMBED_DIM
    embedded = 0
    for chunk, vector in zip(chunks, vectors):
        if not vector:
            continue
        if len(vector) != dim:
            log.warning("块 %s 维度 %d != %d，跳过", chunk["id"], len(vector), dim)
            continue
        chunk["embedding"] = store.encode_vector(vector)
        chunk["dim"] = len(vector)
        embedded += 1

    db = store.init_db(Path(args.db))
    try:
        store.rebuild(db, data["docs"], chunks)
    finally:
        db.close()

    print(f'docs={len(data["docs"])} chunks={len(chunks)} dim={dim} '
          f'embedded={embedded} db={args.db}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
