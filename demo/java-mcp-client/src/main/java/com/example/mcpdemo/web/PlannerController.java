package com.example.mcpdemo.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1 步 3：规划模型连通性验证。
 *
 * <p>用 Spring AI 自动装配的 {@code ChatClient.Builder} 调用本地 Ollama 模型
 * （配置见 application.yml 的 {@code spring.ai.ollama.*}），
 * 验证"规划型 Host"的 LLM 通道可用。
 */
@RestController
@RequestMapping("/api")
public class PlannerController {

    /** 规划用 LLM 客户端（由自动装配的 Builder 构建，模型/温度来自 application.yml）。 */
    private final ChatClient chatClient;

    public PlannerController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/planner/ping")
    public String ping() {
        String reply = chatClient.prompt()
                .user("请只回复两个字：收到")
                .call()
                .content();
        return "模型返回: " + reply;
    }
}
