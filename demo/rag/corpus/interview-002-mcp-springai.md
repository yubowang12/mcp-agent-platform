---
id: interview-002
title: 面试问答 · MCP 与 Spring AI
source: 自整理
date: 2026-04-12
---

## 问：MCP 是什么？

答：Model Context Protocol，一个开放协议，用统一的方式把外部能力（工具、资源、提示）暴露给模型宿主。Host 负责协调，Client 维护连接，Server 暴露能力。好处是工具实现与宿主解耦，换宿主或换工具都不需要改对方代码。

## 问：Streamable HTTP 与 SSE 的区别？

答：SSE 是单向的服务端推送；Streamable HTTP 在 POST 之上支持流式响应与会话恢复，更适合 Agent 这种长任务、多轮交互的场景。

## 问：Spring AI 怎么接 MCP？

答：starter 自动读取配置里的 connection 列表并建立客户端；工具通过 ToolCallback 暴露给 ChatClient，名字加前缀避免冲突。规划循环里手动关闭内部工具执行，由编排器自己执行 tool_calls 并回填结果。

## 问：工具描述为什么重要？

答：模型只看得到 name、description、inputSchema。描述里写清"不做什么"比只写"做什么"更能防错选；越界任务如果硬套工具，容易产生幻觉。
