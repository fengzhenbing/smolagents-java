package org.fzb.smolagents.executor;

import java.util.List;

/**
 * 暴露 Python 执行器实际生效的模块导入白名单，用于生成准确的 Agent 提示。
 */
public interface ImportPolicyProvider {
    /**
     * 返回当前执行环境允许导入的模块白名单。
     *
     * @return 不可变导入名称列表；包含 {@code *} 时代表执行器允许任意 import
     */
    List<String> authorizedImports();
}
