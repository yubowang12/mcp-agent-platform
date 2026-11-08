package com.example.mcpdemo.agent.model;

/**
 * 编排器状态机。
 */
public enum AgentState {
    /** 初始状态：请求刚创建，还没开始跑。 */
    IDLE,
    /** 正在跑 ReAct 循环（think → act → 回填）。 */
    RUNNING,
    /** 正常结束（模型给出答复或调用 terminate）。 */
    FINISHED,
    /** 编排异常（超时、LLM 调用失败等），由 catch 分支置位。 */
    ERROR
}
