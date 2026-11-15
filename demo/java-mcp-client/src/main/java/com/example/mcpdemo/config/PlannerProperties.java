package com.example.mcpdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 编排层（规划型 Host）配置。
 *
 * <p>对应 {@code application.yml} 中 {@code mcp.planner.*}，也可通过命令行覆盖，例如：
 * {@code --mcp.planner.model=qwen2.5:3b}（M6 评测脚本批量跑不同配置时使用）。
 */
@ConfigurationProperties(prefix = "mcp.planner")
public record PlannerProperties(
        /** 规划模型名。 */
        @DefaultValue("qwen2.5:7b") String model,
        /** 任务总超时（秒）。 */
        @DefaultValue("300") int taskTimeoutSeconds,
        /** 单次 LLM 调用超时（秒）。 */
        @DefaultValue("60") int llmTimeoutSeconds,
        /** 单次工具调用超时（秒）。 */
        @DefaultValue("45") int toolTimeoutSeconds,
        /** LLM 调用线程池大小（W2：有界池，满了直接拒绝，不再无界堆线程）。 */
        @DefaultValue("8") int llmPoolSize,
        /** LLM 调用等待队列长度（超出即拒绝）。 */
        @DefaultValue("32") int llmQueue,
        /** 工具调用线程池大小。 */
        @DefaultValue("16") int toolPoolSize,
        /** 工具调用等待队列长度。 */
        @DefaultValue("64") int toolQueue,
        /** 入口并发闸门：同时执行的 /api/task 上限，超出返回 503。 */
        @DefaultValue("8") int maxConcurrentTasks) {
}
