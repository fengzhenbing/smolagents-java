package org.fzb.smolagents.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述可调用 Java 工具方法中的一个 JSON 参数。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolArgument {
    /**
     * 返回 JSON schema 中使用的参数名称。
     *
     * @return 参数名称
     */
    String name();

    /**
     * 返回提供给模型的参数描述。
     *
     * @return 参数描述
     */
    String description() default "";

    /**
     * 返回参数是否必填。
     *
     * @return 必填参数时为 {@code true}；可选且非 nullable 的参数必须声明默认值
     */
    boolean required() default true;

    /**
     * 返回参数是否接受 JSON null。
     *
     * @return 接受 null 时为 {@code true}
     */
    boolean nullable() default false;

    /**
     * 返回是否为省略参数配置了 JSON 默认值。
     *
     * @return 存在默认值时为 {@code true}
     */
    boolean hasDefault() default false;

    /**
     * 返回供模型和 Java 方法使用的 JSON 编码默认值。
     *
     * <p>仅在 {@link #hasDefault()} 为 {@code true} 时读取；此时须同时设置 {@code required = false}，
     * 且值必须是合法 JSON 并符合参数类型。</p>
     *
     * @return JSON 文本，例如 {@code false}、{@code 3} 或 {@code "\"default\""}
     */
    String defaultValueJson() default "";
}
