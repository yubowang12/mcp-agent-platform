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
        @DefaultValue("45") int toolTimeoutSeconds) {
}
