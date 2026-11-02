# M6 评测报告：dual 组

## 运行配置

| 项 | 值 |
|---|---|
| runId | `20261102T120909Z-dual` |
| 组 | `dual`（/api/task） |
| 任务集版本 | `m6-1` |
| 任务数 | 30 |
| 规划模型 | `qwen2.5:7b` |
| 开始时间 | 2026-11-02T12:09:09.234015+00:00 |
| 基础地址 | `http://localhost:8090` |

## 总体指标

| 指标 | 数值 |
|---|---|
| 给出答案率 | 30/30（100.0%） |
| 严格成功率（status=OK 且工具序列完全匹配） | 25/30（83.3%） |
| 宽松成功率（答案非空且所需工具都调到了） | 27/30（90.0%） |
| 工具序列完全匹配率 | 25/30（83.3%） |
| 平均步数 | 1.37 |
| 含控制动作（terminate）的任务数 | 2 |
| 延迟 P50 | 2692 ms |
| 延迟 P95 | 7276 ms |
| 延迟均值 | 3312 ms |

> 口径说明：工具序列比较剔除了 `terminate`（编排层的结束动作，不是能力选择），它单独记在 `controlActions` 列。

## 分类指标

| 类别 | 条数 | 给出答案 | 严格成功 | 宽松成功 | P50 (ms) | P95 (ms) |
|---|---|---|---|---|---|---|
| single | 6 | 6 | 6 | 6 | 2154 | 6692 |
| two | 6 | 6 | 5 | 6 | 5329 | 7223 |
| three | 6 | 6 | 5 | 6 | 5128 | 7968 |
| out_of_scope | 6 | 6 | 5 | 5 | 2193 | 7276 |
| missing_arg | 6 | 6 | 4 | 4 | 790 | 4764 |

## 失败模式分布

| 模式 | 次数 | 任务 |
|---|---|---|
| sequence_mismatch | 2 | t3, h3 |
| wrong_tool | 3 | o1, m4, m6 |

## 失败明细

| id | 期望工具 | 实际工具 | 控制动作 | status | errorCode | 答案预览 |
|---|---|---|---|---|---|---|
| t3 | agentworker_translate, agentworker_summarize | agentworker_translate, agentworker_summarize, agentworker_summarize | - | OK | - | MCP协议标准化多智能体协作平台工具调用。 |
| h3 | demotools_subtract, demotools_add, demotools_subtract | demotools_add, demotools_subtract | - | OK | - | 最终结果是 40。 |
| o1 | （不调工具） | agentworker_run_task | - | OK | - | 明天北京的天气预计多云转晴，气温在-4°C到5°C之间，建议出门携带外套。请注意，这是基于当前可获取信息的预测，实际天气 |
| m4 | （不调工具） | agentworker_translate, agentworker_summarize | - | OK | - | Translate the following text into English: "把这段话翻译成英文."   Th |
| m6 | （不调工具） | agentworker_summarize | - | OK | - | 要点：保留关键点，不超过 50 字。 |

## 最慢的 5 条

| id | 类别 | 延迟 (ms) | 步数 | status |
|---|---|---|---|---|
| h2 | three | 7968 | 3 | OK |
| o1 | out_of_scope | 7276 | 1 | OK |
| t3 | two | 7223 | 3 | OK |
| s4 | single | 6692 | 1 | OK |
| t6 | two | 6587 | 2 | OK |
