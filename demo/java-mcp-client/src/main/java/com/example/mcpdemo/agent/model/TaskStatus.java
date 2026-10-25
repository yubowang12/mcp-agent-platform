package com.example.mcpdemo.agent.model;

/**
 * 任务终态（写入 trace.status）。
 */
public enum TaskStatus {
    /** 正常完成（模型不再调工具，或调用了 terminate）。 */
    OK,
    /** 达到 maxSteps 仍未完成。 */
    MAX_STEPS,
    /** 超过任务总超时。 */
    TIMEOUT,
    /** 编排过程异常。 */
    ERROR,
    /** 走降级策略（M2 预留）。 */
    DEGRADED
}
