"""M6 批量评测脚本：按任务集批量调用编排层，产出原始记录 + 汇总 CSV + 可读报告。

用法（先在 8090 起 Java 编排层、8001/8002 起两个 Python 服务）：
    python demo/eval/run_eval.py --group dual
    python demo/eval/run_eval.py --group direct
    python demo/eval/run_eval.py --group dual --limit 3        # 冒烟
    python demo/eval/run_eval.py --rescore demo/eval/results/<runId>   # 只重算指标，不重新调用

两个组：
    dual   规划型 Host：POST /api/task（有工具、有 trace）
    direct 阶段 0 直通：POST /api/agent/run（无规划、无工具，仅一次 LLM 调用）

判定口径（重要）：
    - 工具序列比较时**剔除 `terminate`**：它是编排层的控制动作（结束任务），不是能力选择；
      被剔除的数量记在 `controlActions` 里，报告单独统计。
    - 严格成功：status=OK 且工具序列完全匹配且答案非空。
    - 宽松成功：答案非空且金标准里的工具都调到了（允许多余步骤）。

产物（每跑一次一个目录）：
    demo/eval/results/<runId>/
        manifest.json / raw.jsonl / summary.csv / report.md / manual_review.md

只依赖标准库（urllib / json / csv），不引新依赖。
"""

import argparse
import csv
import json
import random
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_BASE = "http://localhost:8090"
EVAL_DIR = Path(__file__).resolve().parent

# 控制类工具：属于编排流程，不参与“能力选择”评分
CONTROL_TOOLS = {"terminate"}


def post_json(url: str, payload: dict, timeout: int = 240) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run_dual(base: str, task: str, max_steps: int) -> dict:
    """规划型 Host：有规划、有工具、有 trace。"""
    started = time.perf_counter()
    try:
        r = post_json(f"{base}/api/task", {"task": task, "maxSteps": max_steps})
    except Exception as exc:  # noqa: BLE001 - 评测需要把失败也记成数据
        return {"status": "HTTP_ERROR", "tools": [], "steps": 0, "latencyMs": None,
                "wallMs": int((time.perf_counter() - started) * 1000),
                "answer": "", "errorCode": "HTTP_ERROR", "errorMessage": str(exc),
                "degraded": None, "degradedSteps": [], "taskId": None, "model": None}
    wall = int((time.perf_counter() - started) * 1000)
    steps = r.get("steps") or []
    return {
        "status": r.get("status"),
        "tools": [s.get("tool") for s in steps],
        "steps": len(steps),
        "latencyMs": r.get("totalLatencyMs"),
        "wallMs": wall,
        "answer": r.get("finalAnswer") or "",
        "errorCode": r.get("errorCode"),
        "errorMessage": r.get("errorMessage"),
        "degraded": r.get("degraded"),
        "degradedSteps": r.get("degradedSteps") or [],
        "taskId": r.get("taskId"),
        "model": r.get("model"),
    }


def run_direct(base: str, task: str) -> dict:
    """阶段 0 直通：没有规划、没有工具，只把任务转给 agent-worker 的 run_task。"""
    started = time.perf_counter()
    try:
        r = post_json(f"{base}/api/agent/run", {"task": task})
    except Exception as exc:  # noqa: BLE001
        return {"status": "HTTP_ERROR", "tools": [], "steps": 0, "latencyMs": None,
                "wallMs": int((time.perf_counter() - started) * 1000),
                "answer": "", "errorCode": "HTTP_ERROR", "errorMessage": str(exc),
                "degraded": None, "degradedSteps": [], "taskId": None, "model": None}
    wall = int((time.perf_counter() - started) * 1000)
    ok = bool(r.get("success"))
    return {
        "status": "OK" if ok else "ERROR",
        "tools": [],
        "steps": 0,
        "latencyMs": wall,
        "wallMs": wall,
        "answer": r.get("result") or "",
        "errorCode": None if ok else "DIRECT_ERROR",
        "errorMessage": None,
        "degraded": None,
        "degradedSteps": [],
        "taskId": None,
        "model": None,
    }


def split_control_actions(tools: list) -> tuple:
    """把 terminate 这类控制动作从工具序列里分离出去。"""
    return [t for t in tools if t not in CONTROL_TOOLS], [t for t in tools if t in CONTROL_TOOLS]


def score(run: dict, expected: list, mode: str) -> dict:
    """按金标准打分。direct 组没有工具，只统计“是否给出答案”。"""
    actual = run["tools"]
    answered = bool((run["answer"] or "").strip())
    if mode == "direct":
        return {"strict": None, "lenient": None, "answered": answered,
                "missing": [], "extra": [], "toolsMatch": None}

    tools_match = actual == expected
    strict = run["status"] == "OK" and tools_match and answered
    if expected:
        lenient = answered and all(t in actual for t in expected)
    else:
        lenient = answered and actual == []
    return {
        "strict": strict,
        "lenient": lenient,
        "answered": answered,
        "missing": [t for t in expected if t not in actual],
        "extra": [t for t in actual if t not in expected],
        "toolsMatch": tools_match,
    }


def failure_modes(run: dict, sc: dict, mode: str) -> list:
    if mode == "direct":
        return [] if run["status"] == "OK" else ["failed"]
    modes = []
    if run["status"] in ("ERROR", "TIMEOUT", "MAX_STEPS", "HTTP_ERROR"):
        modes.append("failed")
    if run["status"] == "DEGRADED":
        modes.append("degraded")
    if sc["extra"]:
        modes.append("wrong_tool")
    if sc["missing"]:
        modes.append("missing_tool")
    if not modes and not sc["toolsMatch"] and run["status"] == "OK":
        modes.append("sequence_mismatch")
    return modes


def make_row(item: dict, run: dict, mode: str) -> dict:
    """统一的行结构：剥离控制动作 → 打分 → 失败模式。"""
    tools, controls = split_control_actions(run.get("tools") or [])
    run = {**run, "tools": tools}
    sc = score(run, item["expectedTools"], mode)
    return {
        "id": item["id"], "category": item["category"], "task": item["task"],
        "expectedTools": item["expectedTools"], "expectedBehavior": item.get("expectedBehavior"),
        **run, **sc, "controlActions": controls, "modes": failure_modes(run, sc, mode),
    }


def percentile(values: list, pct: float) -> float:
    if not values:
        return 0.0
    values = sorted(values)
    rank = max(int(round(pct / 100.0 * len(values) + 0.5)) - 1, 0)
    return float(values[min(rank, len(values) - 1)])


def fmt_pct(num: int, den: int) -> str:
    return f"{num}/{den}（{num / den * 100:.1f}%）" if den else "n/a"


def build_report(manifest: dict, rows: list, mode: str) -> str:
    total = len(rows)
    answered = sum(1 for r in rows if r["answered"])
    controls = sum(1 for r in rows if r["controlActions"])
    lines = []
    lines.append(f"# M6 评测报告：{manifest['group']} 组\n")
    lines.append("## 运行配置\n")
    lines.append("| 项 | 值 |")
    lines.append("|---|---|")
    lines.append(f"| runId | `{manifest['runId']}` |")
    lines.append(f"| 组 | `{manifest['group']}`（{manifest['endpoint']}） |")
    lines.append(f"| 任务集版本 | `{manifest['tasksVersion']}` |")
    lines.append(f"| 任务数 | {total} |")
    lines.append(f"| 规划模型 | `{manifest['model']}` |")
    lines.append(f"| 开始时间 | {manifest['startedAt']} |")
    lines.append(f"| 基础地址 | `{manifest['base']}` |")
    lines.append("")

    latencies = [r["latencyMs"] for r in rows if r["latencyMs"] is not None]
    lines.append("## 总体指标\n")
    lines.append("| 指标 | 数值 |")
    lines.append("|---|---|")
    lines.append(f"| 给出答案率 | {fmt_pct(answered, total)} |")
    if mode == "dual":
        strict = sum(1 for r in rows if r["strict"])
        lenient = sum(1 for r in rows if r["lenient"])
        lines.append(f"| 严格成功率（status=OK 且工具序列完全匹配） | {fmt_pct(strict, total)} |")
        lines.append(f"| 宽松成功率（答案非空且所需工具都调到了） | {fmt_pct(lenient, total)} |")
        lines.append(f"| 工具序列完全匹配率 | {fmt_pct(sum(1 for r in rows if r['toolsMatch']), total)} |")
        lines.append(f"| 平均步数 | {statistics.mean([r['steps'] for r in rows]):.2f} |")
        lines.append(f"| 含控制动作（terminate）的任务数 | {controls} |")
    lines.append(f"| 延迟 P50 | {percentile(latencies, 50):.0f} ms |")
    lines.append(f"| 延迟 P95 | {percentile(latencies, 95):.0f} ms |")
    lines.append(f"| 延迟均值 | {statistics.mean(latencies):.0f} ms |" if latencies else "| 延迟均值 | n/a |")
    lines.append("")
    lines.append("> 口径说明：工具序列比较剔除了 `terminate`（编排层的结束动作，不是能力选择），"
                 "它单独记在 `controlActions` 列。\n")

    lines.append("## 分类指标\n")
    lines.append("| 类别 | 条数 | 给出答案 | 严格成功 | 宽松成功 | P50 (ms) | P95 (ms) |")
    lines.append("|---|---|---|---|---|---|---|")
    for cat in dict.fromkeys(r["category"] for r in rows):
        group = [r for r in rows if r["category"] == cat]
        lat = [r["latencyMs"] for r in group if r["latencyMs"] is not None]
        ans = sum(1 for r in group if r["answered"])
        if mode == "dual":
            strict = sum(1 for r in group if r["strict"])
            lenient = sum(1 for r in group if r["lenient"])
            lines.append(f"| {cat} | {len(group)} | {ans} | {strict} | {lenient} | "
                         f"{percentile(lat, 50):.0f} | {percentile(lat, 95):.0f} |")
        else:
            lines.append(f"| {cat} | {len(group)} | {ans} | - | - | "
                         f"{percentile(lat, 50):.0f} | {percentile(lat, 95):.0f} |")
    lines.append("")

    if mode == "dual":
        lines.append("## 失败模式分布\n")
        lines.append("| 模式 | 次数 | 任务 |")
        lines.append("|---|---|---|")
        buckets = {}
        for r in rows:
            for m in r["modes"]:
                buckets.setdefault(m, []).append(r["id"])
        if not buckets:
            lines.append("| （无） | 0 | - |")
        for mode_name, ids in sorted(buckets.items()):
            lines.append(f"| {mode_name} | {len(ids)} | {', '.join(ids)} |")
        lines.append("")

    failures = [r for r in rows if (r["strict"] is False if mode == "dual" else not r["answered"])]
    lines.append("## 失败明细\n")
    if not failures:
        lines.append("无。\n")
    else:
        lines.append("| id | 期望工具 | 实际工具 | 控制动作 | status | errorCode | 答案预览 |")
        lines.append("|---|---|---|---|---|---|---|")
        for r in failures:
            exp = ", ".join(r["expectedTools"]) or "（不调工具）"
            act = ", ".join(r["tools"]) or "（未调工具）"
            ctrl = ", ".join(r["controlActions"]) or "-"
            preview = (r["answer"] or "").replace("\n", " ")[:60]
            lines.append(f"| {r['id']} | {exp} | {act} | {ctrl} | {r['status']} | "
                         f"{r['errorCode'] or '-'} | {preview} |")
        lines.append("")

    slowest = sorted([r for r in rows if r["latencyMs"]], key=lambda r: r["latencyMs"], reverse=True)[:5]
    lines.append("## 最慢的 5 条\n")
    lines.append("| id | 类别 | 延迟 (ms) | 步数 | status |")
    lines.append("|---|---|---|---|---|")
    for r in slowest:
        lines.append(f"| {r['id']} | {r['category']} | {r['latencyMs']} | {r['steps']} | {r['status']} |")
    lines.append("")
    return "\n".join(lines)


def build_manual_review(rows: list, sample_size: int, seed: int) -> str:
    rng = random.Random(seed)
    picked = rng.sample(rows, min(sample_size, len(rows)))
    lines = ["# 人工复核抽样\n",
             "对每条任务的答案质量打分：`对` / `部分对` / `错`，并说明理由。", ""]
    for r in picked:
        lines.append(f"## {r['id']}（{r['category']}）\n")
        lines.append(f"- 任务：{r['task']}")
        lines.append(f"- 期望工具：{', '.join(r['expectedTools']) or '（不调工具）'}")
        lines.append(f"- 实际工具：{', '.join(r['tools']) or '（未调工具）'}")
        lines.append(f"- status：`{r['status']}`，errorCode：`{r['errorCode'] or '-'}`")
        lines.append(f"- 答案：\n\n> {r['answer'].strip() or '（空）'}\n")
        lines.append("- 判定：____　理由：____\n")
    return "\n".join(lines)


def write_outputs(out_dir: Path, manifest: dict, rows: list, mode: str, sample: int, seed: int) -> None:
    (out_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    with (out_dir / "summary.csv").open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["id", "category", "status", "strict", "lenient", "answered", "steps",
                         "tools", "expected", "controlActions", "latencyMs", "errorCode", "modes",
                         "answerPreview"])
        for r in rows:
            writer.writerow([r["id"], r["category"], r["status"], r["strict"], r["lenient"],
                             r["answered"], r["steps"], "|".join(r["tools"]),
                             "|".join(r["expectedTools"]), "|".join(r["controlActions"]),
                             r["latencyMs"], r["errorCode"] or "",
                             "|".join(r["modes"]), (r["answer"] or "").replace("\n", " ")[:80]])
    (out_dir / "report.md").write_text(build_report(manifest, rows, mode), encoding="utf-8")
    (out_dir / "manual_review.md").write_text(build_manual_review(rows, sample, seed), encoding="utf-8")


def rescore(out_dir: Path, sample: int, seed: int) -> int:
    """只重算指标（不重新调用 API），用于口径调整后复算历史数据。"""
    manifest = json.loads((out_dir / "manifest.json").read_text(encoding="utf-8"))
    records = [json.loads(line) for line in (out_dir / "raw.jsonl").read_text(encoding="utf-8").splitlines()
               if line.strip()]
    rows = [make_row(rec["task"], rec["run"], manifest["group"]) for rec in records]
    write_outputs(out_dir, manifest, rows, manifest["group"], sample, seed)
    strict = sum(1 for r in rows if r["strict"]) if manifest["group"] == "dual" else 0
    answered = sum(1 for r in rows if r["answered"])
    print(f"重算完成：{out_dir}")
    if manifest["group"] == "dual":
        print(f"  严格成功 {strict}/{len(rows)}，给出答案 {answered}/{len(rows)}")
    else:
        print(f"  给出答案 {answered}/{len(rows)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="M6 批量评测")
    parser.add_argument("--group", choices=["dual", "direct"], default="dual")
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--tasks", default=str(EVAL_DIR / "tasks.json"))
    parser.add_argument("--out", default=None, help="默认放到 results/<runId>")
    parser.add_argument("--limit", type=int, default=None, help="只跑前 N 条（冒烟用）")
    parser.add_argument("--sample", type=int, default=10, help="人工复核抽样条数")
    parser.add_argument("--seed", type=int, default=20261102, help="抽样随机种子，保证可复现")
    parser.add_argument("--rescore", default=None, help="只重算指标：传入已有结果目录")
    args = parser.parse_args()

    if args.rescore:
        return rescore(Path(args.rescore), args.sample, args.seed)

    spec = json.loads(Path(args.tasks).read_text(encoding="utf-8"))
    tasks = spec["tasks"][: args.limit] if args.limit else spec["tasks"]
    max_steps = spec.get("maxSteps", 10)

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + args.group
    out_dir = Path(args.out) if args.out else EVAL_DIR / "results" / run_id
    out_dir.mkdir(parents=True, exist_ok=True)

    manifest = {
        "runId": run_id,
        "group": args.group,
        "endpoint": "/api/task" if args.group == "dual" else "/api/agent/run",
        "base": args.base,
        "tasksVersion": spec.get("version", "unknown"),
        "tasksFile": str(Path(args.tasks)),
        "taskCount": len(tasks),
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "model": None,
        "python": sys.version.split()[0],
    }

    rows, raw = [], []
    print(f"[{args.group}] {len(tasks)} 条任务，输出到 {out_dir}")
    for item in tasks:
        run = (run_dual(args.base, item["task"], max_steps) if args.group == "dual"
               else run_direct(args.base, item["task"]))
        row = make_row(item, run, args.group)
        rows.append(row)
        raw.append({"task": item, "run": run, "score": {k: row[k] for k in ("strict", "lenient", "answered", "missing", "extra", "toolsMatch")},
                    "modes": row["modes"], "controlActions": row["controlActions"]})
        if manifest["model"] is None and run.get("model"):
            manifest["model"] = run["model"]
        ok = row["strict"] if args.group == "dual" else row["answered"]
        mark = "OK " if ok else "NG "
        print(f"  {mark}{item['id']:<3} {run['status']:<8} {run['latencyMs'] or 0:>6}ms "
              f"{len(row['tools'])}步 {'/'.join(row['tools']) or '-'}")

    manifest["model"] = manifest["model"] or "qwen2.5:7b"
    manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
    with (out_dir / "raw.jsonl").open("w", encoding="utf-8") as fh:
        for record in raw:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
    write_outputs(out_dir, manifest, rows, args.group, args.sample, args.seed)

    print(f"\n完成：{out_dir}")
    print(f"  报告：{(out_dir / 'report.md')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
