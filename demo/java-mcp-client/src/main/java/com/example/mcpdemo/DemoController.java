package com.example.mcpdemo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.mcpdemo.resilience.CircuitBreaker;
import com.example.mcpdemo.resilience.CircuitBreakerRegistry;
import com.example.mcpdemo.resilience.CircuitState;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 技术验证接口。
 *
 * 演示 Java 编排层通过 MCP 协议调用 Python 微服务：
 *   GET  /api/servers         查看已连接的 MCP Server（含熔断健康状态）
 *   GET  /api/tools           查看所有已注册工具
 *   GET  /api/demo/get-time   调用工具微服务 get_time
 *   GET  /api/demo/add        调用工具微服务 add
 *   POST /api/agent/run       向 Agent 下发自然语言任务（完整闭环）
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    /** 已连接的 MCP 客户端（每台 Python MCP Server 一个，由 starter 自动装配）。 */
    private final List<McpSyncClient> clients;
    /** 按 server 名管理的熔断器（M5：/api/servers 用它展示健康状态）。 */
    private final CircuitBreakerRegistry circuitBreakers;

    public DemoController(List<McpSyncClient> clients, CircuitBreakerRegistry circuitBreakers) {
        this.clients = clients;
        this.circuitBreakers = circuitBreakers;
    }

    /** 列出所有已连接的 MCP Server（含熔断状态，M5）。 */
    @GetMapping("/servers")
    public List<Map<String, Object>> servers() {
        return clients.stream()
                .map(c -> {
                    McpSchema.Implementation info = c.getServerInfo();
                    String server = info != null ? info.name() : "unknown";
                    CircuitBreaker breaker = circuitBreakers.get(server);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("server", server);
                    m.put("version", info != null ? info.version() : "unknown");
                    m.put("healthy", breaker.state() == CircuitState.CLOSED);
                    m.put("state", breaker.state().name());
                    m.put("consecutiveFailures", breaker.consecutiveFailures());
                    return m;
                })
                .toList();
    }

    /** 聚合展示所有 Server 暴露的工具。 */
    @GetMapping("/tools")
    public List<Map<String, String>> tools() {
        return clients.stream()
                .flatMap(c -> {
                    String server = c.getServerInfo() != null ? c.getServerInfo().name() : "unknown";
                    return c.listTools().tools().stream().map(tool -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("server", server);
                        m.put("tool", tool.name());
                        m.put("description", tool.description() == null ? "" : tool.description());
                        return m;
                    });
                })
                .toList();
    }

    /** 调用 demo-tools 的 get_time 工具。 */
    @GetMapping("/demo/get-time")
    public Map<String, Object> getTime() {
        McpSchema.CallToolResult r = callTool("demo-tools", "get_time", Map.of());
        return Map.of("success", !Boolean.TRUE.equals(r.isError()), "result", textOf(r));
    }

    /** 调用 demo-tools 的 add 工具。 */
    @GetMapping("/demo/add")
    public Map<String, Object> add(@RequestParam double a, @RequestParam double b) {
        McpSchema.CallToolResult r = callTool("demo-tools", "add", Map.of("a", a, "b", b));
        return Map.of("success", !Boolean.TRUE.equals(r.isError()), "result", textOf(r));
    }

    /** 向 agent-worker 下发任务，返回 LLM 结果（Java → MCP → Python Agent → Ollama）。 */
    @PostMapping("/agent/run")
    public Map<String, Object> runAgent(@RequestBody Map<String, String> body) {
        String task = body.getOrDefault("task", "你好，介绍一下你自己");
        McpSchema.CallToolResult r = callTool("agent-worker", "run_task", Map.of("task", task));
        return Map.of("success", !Boolean.TRUE.equals(r.isError()), "result", textOf(r));
    }

    /** Agent 健康检查。 */
    @GetMapping("/agent/ping")
    public Map<String, Object> ping() {
        McpSchema.CallToolResult r = callTool("agent-worker", "ping", Map.of());
        return Map.of("success", !Boolean.TRUE.equals(r.isError()), "result", textOf(r));
    }

    // ---------- 内部工具方法 ----------

    /** 按 MCP Server 名找到对应客户端并调用工具。 */
    private McpSchema.CallToolResult callTool(String serverName, String toolName, Map<String, Object> args) {
        McpSyncClient client = clients.stream()
                .filter(c -> c.getServerInfo() != null && serverName.equals(c.getServerInfo().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP Server 未连接: " + serverName));
        return client.callTool(new McpSchema.CallToolRequest(toolName, args));
    }

    /** 提取工具返回结果中的文本内容。 */
    private static String textOf(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.content() != null) {
            for (McpSchema.Content c : result.content()) {
                if (c instanceof McpSchema.TextContent t) {
                    sb.append(t.text());
                }
            }
        }
        return sb.toString();
    }
}
