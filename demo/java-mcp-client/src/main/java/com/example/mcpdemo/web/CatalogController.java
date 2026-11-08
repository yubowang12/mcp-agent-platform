package com.example.mcpdemo.web;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1 步 2：工具目录调试接口（前缀化视图）。
 *
 * <p>返回“模型实际会看到的工具名 / 描述 / 参数 schema”，
 * 用于验证 {@link McpToolNamePrefixGenerator} 生效（如 {@code demotools_add}）。
 */
@RestController
@RequestMapping("/api")
public class CatalogController {

    /** 模型视角的工具目录（前缀化后的工具名/描述/schema，构造时一次性建好）。 */
    private final SyncMcpToolCallbackProvider callbackProvider;

    public CatalogController(List<McpSyncClient> clients, McpToolNamePrefixGenerator prefixGenerator) {
        // 复用前缀 bean 构建 ToolCallback 列表（即模型视角下的工具目录）。
        // 第一个参数是工具过滤器，暂时全部放行。
        this.callbackProvider = new SyncMcpToolCallbackProvider(
                (connection, tool) -> true,
                prefixGenerator,
                clients.toArray(McpSyncClient[]::new));
    }

    @GetMapping("/catalog")
    public List<Map<String, String>> catalog() {
        return Arrays.stream(callbackProvider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(def -> Map.of(
                        "name", def.name(),
                        "description", def.description() == null ? "" : def.description(),
                        "inputSchema", def.inputSchema() == null ? "{}" : def.inputSchema()))
                .toList();
    }
}
