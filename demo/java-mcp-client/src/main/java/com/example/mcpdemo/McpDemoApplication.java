package com.example.mcpdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MCP 技术验证 Demo 入口。
 *
 * 本应用作为 MCP Client（编排层），启动时自动通过 stdio 拉起并连接：
 *   - demo-tools   : Python 工具微服务（get_time / add）
 *   - agent-worker : Python 智能体微服务（run_task / ping）
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class McpDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpDemoApplication.class, args);
    }
}
