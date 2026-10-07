package org.fzb.smolagents.executor.remote;

/**
 * E2B 沙箱生命周期适配钩子。
 *
 * <p>E2B Python 参考实现调用 SDK 的 {@code Sandbox.run_code()}，并非 Jupyter API。本适配器只接受由调用方
 * 明确提供的 Jupyter Gateway 沙箱；它不会声称实现原生 E2B Code Interpreter SDK。</p>
 */
public interface E2BSessionProvider extends JupyterSandboxProvider {
}
