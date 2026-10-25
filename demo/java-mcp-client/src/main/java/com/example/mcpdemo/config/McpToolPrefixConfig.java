package com.example.mcpdemo.config;

import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M1 步 2：工具名前缀配置。
 *
 * <p>为什么需要：function calling 的工具名必须匹配 {@code ^[a-zA-Z0-9_-]{1,64}$}，
 * 而不同 MCP Server 之间工具可能重名，所以给模型看到的工具名统一加
 * {@code <server>_<tool>} 前缀，例如 {@code demo-tools.add -> demotools_add}。
 *
 * <p>注意：当前前缀源用 {@code serverInfo.name()}（M1 的简化）；
 * 后续切换注册中心（R1）时改为以 yml connections key 作为权威身份。
 */
@Configuration
public class McpToolPrefixConfig {

    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return (connection, tool) -> {
            String server = "server";
            if (connection != null
                    && connection.initializeResult() != null
                    && connection.initializeResult().serverInfo() != null) {
                server = connection.initializeResult().serverInfo().name();
            }
            return sanitize(server) + "_" + sanitize(tool.name());
        };
    }

    /** 去掉字母/数字/下划线以外的字符，例如 "demo-tools" -> "demotools"。 */
    public static String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "server";
        }
        return s.replaceAll("[^a-zA-Z0-9_]", "");
    }
}
