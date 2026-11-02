# 人工复核抽样

对每条任务的答案质量打分：`对` / `部分对` / `错`，并说明理由。

## o1（out_of_scope）

- 任务：帮我查一下明天北京的天气
- 期望工具：（不调工具）
- 实际工具：agentworker_run_task
- status：`OK`，errorCode：`-`
- 答案：

> 明天北京的天气预计多云转晴，气温在-4°C到5°C之间，建议出门携带外套。请注意，这是基于当前可获取信息的预测，实际天气可能会有所变化，请出行前关注最新天气预报。

- 判定：____　理由：____

## s3（single）

- 任务：100 减 37 等于多少？
- 期望工具：demotools_subtract
- 实际工具：demotools_subtract
- status：`OK`，errorCode：`-`
- 答案：

> 100 减 37 等于 63。

- 判定：____　理由：____

## h1（three）

- 任务：先算 10 减 1，再加 2，最后把结果翻译成英文
- 期望工具：demotools_subtract, demotools_add, agentworker_translate
- 实际工具：demotools_subtract, demotools_add, agentworker_translate
- status：`OK`，errorCode：`-`
- 答案：

> The result of your calculation is nine, which is translated to "nine" in English.

- 判定：____　理由：____

## o3（out_of_scope）

- 任务：帮我给张三发一封邮件，告诉他会议改到下午三点
- 期望工具：（不调工具）
- 实际工具：（未调工具）
- status：`OK`，errorCode：`-`
- 答案：

> 当前工具集无法直接发送邮件，请使用其他工具或手动操作。终止任务。

- 判定：____　理由：____

## m3（missing_arg）

- 任务：帮我算一下
- 期望工具：（不调工具）
- 实际工具：（未调工具）
- status：`OK`，errorCode：`-`
- 答案：

> 请提供你需要计算的具体数字和运算符号，例如：“15 + 27”。

- 判定：____　理由：____

## s6（single）

- 任务：用一句话介绍你自己
- 期望工具：（不调工具）
- 实际工具：（未调工具）
- status：`OK`，errorCode：`-`
- 答案：

> 我是一个能够帮助你完成各种任务的智能助手。

- 判定：____　理由：____

## s4（single）

- 任务：把这段话压缩成不超过 60 字的要点：MCP 是一个开放协议，用于标准化 AI 应用与外部工具之间的连接。它把能力抽象成工具、资源、提示三类，Host 负责协调，Client 维护连接，Server 暴露能力。
- 期望工具：agentworker_summarize
- 实际工具：agentworker_summarize
- status：`OK`，errorCode：`-`
- 答案：

> MCP 是开放协议，标准化 AI 与外部工具连接，分为工具、资源、提示三类。Host 协调，Client 维护，Server 暴露能力。

- 判定：____　理由：____

## h2（three）

- 任务：先查现在几点，再把时间压缩成不超过 20 字的要点，最后翻译成英文
- 期望工具：demotools_get_time, agentworker_summarize, agentworker_translate
- 实际工具：demotools_get_time, agentworker_summarize, agentworker_translate
- status：`OK`，errorCode：`-`
- 答案：

> 当前时间要点：2026年11月2日20:09:59。
翻译成英文：November 2, 2026 20:09:59。

- 判定：____　理由：____

## s2（single）

- 任务：12.5 加 7.5 等于多少？
- 期望工具：demotools_add
- 实际工具：demotools_add
- status：`OK`，errorCode：`-`
- 答案：

> 12.5 加 7.5 等于 20.0。

- 判定：____　理由：____

## m2（missing_arg）

- 任务：帮我压缩一下
- 期望工具：（不调工具）
- 实际工具：（未调工具）
- status：`OK`，errorCode：`-`
- 答案：

> 请提供需要压缩的原文。

- 判定：____　理由：____
