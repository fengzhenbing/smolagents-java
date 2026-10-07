package org.fzb.smolagents.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.fzb.smolagents.core.AgentRunException;
import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ModelOutputException;
import org.fzb.smolagents.core.ToolExecutionException;
import org.fzb.smolagents.model.ToolCall;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理工具定义、名称唯一性、参数校验及工具调用分发。
 */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ToolArgumentValidator validator;

    /**
     * 创建使用 JSON Schema Draft 2020-12 的默认工具注册表。
     */
    public ToolRegistry() {
        this(new JsonSchemaToolArgumentValidator());
    }

    /**
     * 创建使用指定参数校验器的工具注册表。
     *
     * @param validator 工具参数校验器
     */
    public ToolRegistry(ToolArgumentValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * 注册启用的工具；名称重复时拒绝覆盖已有定义。
     *
     * @param tool 待注册工具
     * @throws ConfigurationException 工具禁用或名称重复时抛出
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        ToolSpec spec = Objects.requireNonNull(tool.spec(), "tool spec");
        if (!spec.enabled()) {
            throw new ConfigurationException("cannot register disabled tool: " + spec.name());
        }
        Tool existing = tools.putIfAbsent(spec.name(), tool);
        if (existing != null) {
            throw new ConfigurationException("duplicate tool name: " + spec.name());
        }
    }

    /**
     * 按模型返回的工具名查找已注册工具。
     *
     * @param name 工具名称
     * @return 对应工具
     * @throws ModelOutputException 模型请求未注册的工具时抛出
     */
    public Tool get(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ModelOutputException("model requested unknown tool: " + name);
        }
        return tool;
    }

    /**
     * 返回按名称排序的启用工具定义快照。
     *
     * @return 工具 schema 列表
     */
    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(Tool::spec)
                .sorted(Comparator.comparing(ToolSpec::name))
                .toList();
    }

    /**
     * 返回用于 Python 兼容 planning tool 列表的普通工具规格。
     *
     * @return 排除 ManagedAgentTool 后按名称排序的工具规格
     */
    public List<ToolSpec> planningToolSpecs() {
        return toolSpecs(false);
    }

    /**
     * 返回独立呈现于 planning team-member 区域的托管 Agent 规格。
     *
     * @return ManagedAgentTool 规格，按名称排序
     */
    public List<ToolSpec> managedAgentSpecs() {
        return toolSpecs(true);
    }

    /**
     * 先验证调用 ID 和 JSON Schema，再执行工具并检查返回值。
     *
     * @param call 模型生成的工具调用
     * @param context 本次工具执行上下文
     * @return 工具结果
     */
    public ToolResult invoke(ToolCall call, ToolExecutionContext context) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(context, "context");
        if (!call.id().equals(context.callId())) {
            throw new IllegalArgumentException("call ID does not match execution context");
        }
        // 步骤一：解析工具并校验参数，确保不合规输入不会产生副作用。
        Tool tool = get(call.name());
        JsonNode arguments = call.arguments();
        validator.validate(tool.spec().inputSchema(), arguments);
        // 步骤二：执行前再次检查取消标志，并拒绝工具返回 null。
        context.cancellation().throwIfCancelled();
        try {
            return Objects.requireNonNull(
                    tool.execute(arguments, context),
                    "tool execution must return a result"
            );
        } catch (ToolExecutionException error) {
            throw error;
        } catch (AgentRunException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ToolExecutionException(
                    "tool execution failed: " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()),
                    error
            );
        }
    }

    private List<ToolSpec> toolSpecs(boolean managedAgents) {
        return tools.values().stream()
                .filter(tool -> (tool instanceof ManagedAgentTool) == managedAgents)
                .map(Tool::spec)
                .sorted(Comparator.comparing(ToolSpec::name))
                .toList();
    }
}
