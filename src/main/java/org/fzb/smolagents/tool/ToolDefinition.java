package org.fzb.smolagents.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将 Java 方法标记为可由 Agent 调用的工具方法。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolDefinition {
    /**
     * 返回模型可见的工具名称。
     *
     * @return 工具名称
     */
    String name();

    /**
     * 返回模型可见的工具说明。
     *
     * @return 工具说明
     */
    String description();
}
