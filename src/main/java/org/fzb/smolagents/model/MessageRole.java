package org.fzb.smolagents.model;

/**
 * 模型上下文中支持的标准消息角色。
 */
public enum MessageRole {
    /** 系统级指令。 */
    SYSTEM,
    /** 用户任务或 observation。 */
    USER,
    /** Agent 或模型生成的内容。 */
    ASSISTANT,
    /** 对应一次工具调用的工具结果。 */
    TOOL
}
