package com.example.mcpdemo.memory.model;

/**
 * 长期记忆类型（规格第 2 节决策 6）：只做 semantic + episodic，procedural 不做。
 */
public enum MemoryKind {

    /** 偏好 / 事实，例如"我叫小明""我更喜欢 Java"。 */
    SEMANTIC("semantic"),

    /** 会话摘要：这次聊了什么、用了哪些工具、结论是什么。 */
    EPISODIC("episodic");

    /** 落库值（{@code memories.kind} 列存的就是它）。 */
    private final String value;

    MemoryKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MemoryKind from(String value) {
        for (MemoryKind kind : values()) {
            if (kind.value.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知记忆类型: " + value);
    }
}
