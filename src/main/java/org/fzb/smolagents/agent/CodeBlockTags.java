package org.fzb.smolagents.agent;

/**
 * 定义 CodeAgent 模型输出中代码块的 literal opening/closing tags。
 *
 * <p>标记按字面匹配而不是作为可执行正则表达式，避免自定义 prompt 配置引入 regex
 * 注入或不受控的回溯。未指定 tags 时仍兼容默认 XML 标签及 Markdown Python fences。</p>
 *
 * @param openingTag 开始标记
 * @param closingTag 结束标记
 */
public record CodeBlockTags(String openingTag, String closingTag) {
    /**
     * 校验开始、结束 marker 非空且互不相同。
     *
     * @param openingTag 开始标记
     * @param closingTag 结束标记
     */
    public CodeBlockTags {
        if (openingTag == null || openingTag.isEmpty()) {
            throw new IllegalArgumentException("openingTag must not be empty");
        }
        if (closingTag == null || closingTag.isEmpty()) {
            throw new IllegalArgumentException("closingTag must not be empty");
        }
        if (openingTag.equals(closingTag)) {
            throw new IllegalArgumentException("openingTag and closingTag must differ");
        }
    }

    /**
     * 返回 smolagents 默认的 XML code tag。
     *
     * @return {@code <code>} / {@code </code>} 标记
     */
    public static CodeBlockTags xml() {
        return new CodeBlockTags("<code>", "</code>");
    }

    /**
     * 返回 Markdown fenced Python 默认标记。
     *
     * @return Python code fence 标记
     */
    public static CodeBlockTags markdown() {
        return new CodeBlockTags("```python", "```");
    }
}
