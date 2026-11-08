package com.example.mcpdemo.agent;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 终止工具：模型在任务完成或无法继续推进时调用，用于结束规划循环。
 *
 * <p>沿用 OpenManus 风格：无参数、返回固定文本；编排器在工具响应里识别该名字后终止。
 */
public class TerminateTool {

    /** 工具名：编排器靠它识别"模型要求结束"，与 OpenManus 的命名保持一致。 */
    public static final String TOOL_NAME = "terminate";

    @Tool(name = TOOL_NAME, description = "当任务已完成，或无法继续推进时调用本工具来终止任务。")
    public String terminate() {
        return "任务结束";
    }
}
