package org.fzb.smolagents.executor;

import org.fzb.smolagents.core.ConfigurationException;
import org.fzb.smolagents.core.ExecutorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 通过 Docker 容器运行持久 Python worker，限制网络、文件系统和资源访问。
 *
 * <p>容器引擎及镜像必须由部署环境预先安装；默认使用本地已有的
 * {@code python:3.12-slim} 镜像，不会自动拉取镜像。容器隔离仍依赖可信的 Docker
 * daemon、镜像和宿主机内核，不能替代针对高风险多租户执行环境的专用沙箱。</p>
 */
public final class DockerPythonCodeExecutor implements CodeExecutor, ImportPolicyProvider {
    private static final String WORKER_CONTAINER_PATH = "/opt/smolagents/python_worker.py";
    private static final List<String> DEFAULT_AUTHORIZED_IMPORTS = List.of(
            "collections", "datetime", "itertools", "math", "queue", "random",
            "re", "stat", "statistics", "time", "unicodedata"
    );

    private final PythonProcessCodeExecutor delegate;
    private final String containerName;

    /**
     * 使用本地 Docker 命令、预先拉取的 Python 镜像和默认资源限制创建执行器。
     *
     * @param dockerExecutable Docker CLI 可执行文件或命令名
     * @param image 已存在于本地的可信 Python 镜像
     */
    public DockerPythonCodeExecutor(String dockerExecutable, String image) {
        this(
                dockerExecutable,
                image,
                Duration.ofSeconds(30),
                50_000,
                DEFAULT_AUTHORIZED_IMPORTS,
                "512m",
                1.0,
                64
        );
    }

    /**
     * 使用自定义运行时限制创建 Docker Python 执行器。
     *
     * @param dockerExecutable Docker CLI 可执行文件或命令名
     * @param image 已存在于本地的可信 Python 镜像
     * @param defaultTimeout 默认单次执行时限
     * @param maxOutputChars worker stdout 最大字符数
     * @param authorizedImports Python import 白名单
     * @param memoryLimit Docker 内存限制，例如 {@code 512m}
     * @param cpuLimit Docker CPU 核心配额，必须为有限正数
     * @param processLimit 容器允许的最大进程数
     */
    public DockerPythonCodeExecutor(
            String dockerExecutable,
            String image,
            Duration defaultTimeout,
            int maxOutputChars,
            List<String> authorizedImports,
            String memoryLimit,
            double cpuLimit,
            int processLimit
    ) {
        requireText(dockerExecutable, "dockerExecutable");
        requireText(image, "image");
        if (defaultTimeout == null || defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("defaultTimeout must be positive");
        }
        if (memoryLimit == null || !memoryLimit.matches("[1-9][0-9]*[kKmMgG]")) {
            throw new IllegalArgumentException("memoryLimit must be a positive Docker memory value");
        }
        if (!Double.isFinite(cpuLimit) || cpuLimit <= 0) {
            throw new IllegalArgumentException("cpuLimit must be finite and positive");
        }
        if (processLimit < 1) {
            throw new IllegalArgumentException("processLimit must be positive");
        }
        List<String> imports = List.copyOf(authorizedImports);
        this.containerName = "smolagents-" + UUID.randomUUID().toString().replace("-", "");
        this.delegate = new PythonProcessCodeExecutor(
                dockerExecutable,
                defaultTimeout,
                maxOutputChars,
                imports,
                (executable, workerFile) -> dockerLaunchCommand(
                        executable,
                        image,
                        workerFile,
                        memoryLimit,
                        cpuLimit,
                        processLimit,
                        containerName
                ),
                () -> removeContainer(dockerExecutable, containerName)
        );
    }

    /**
     * 使只读挂载的 worker 对容器内非特权用户可读后构造启动参数。
     *
     * @param executable Docker CLI 命令
     * @param image Python worker 镜像
     * @param workerFile 宿主机 worker 文件
     * @param memoryLimit 内存上限
     * @param cpuLimit CPU 配额
     * @param processLimit 进程数上限
     * @return Docker 启动命令
     */
    private static List<String> dockerLaunchCommand(
            String executable,
            String image,
            Path workerFile,
            String memoryLimit,
            double cpuLimit,
            int processLimit,
            String containerName
    ) {
        try {
            Files.setPosixFilePermissions(workerFile, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ
            ));
        } catch (UnsupportedOperationException error) {
            if (!workerFile.toFile().setReadable(true, false)) {
                throw new ExecutorException("Python worker is not readable by the container user", error);
            }
        } catch (IOException error) {
            throw new ExecutorException("Unable to prepare Python worker for read-only container mount", error);
        }
        return dockerCommand(executable, image, workerFile, memoryLimit, cpuLimit, processLimit, containerName);
    }

    /**
     * 在受限容器中执行一次代码动作并处理 Java 工具桥接。
     *
     * @param request 代码、变量、工具和运行限制
     * @return 标准输出、最终表达式结果、变量快照或执行失败
     */
    @Override
    public ExecutorResult execute(ExecutorRequest request) {
        return delegate.execute(request);
    }

    /**
     * 返回传递给容器内 Python worker 的导入白名单。
     *
     * @return 不可变模块名称列表
     */
    @Override
    public List<String> authorizedImports() {
        return delegate.authorizedImports();
    }

    /**
     * 清空当前容器 worker 的 Python 命名空间。
     */
    @Override
    public void reset() {
        delegate.reset();
    }

    /**
     * 关闭 worker 并移除临时脚本及容器运行资源。
     */
    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 构造无网络、只读根文件系统和非特权用户的 Docker 启动参数。
     *
     * @param executable Docker CLI 命令
     * @param image Python worker 镜像
     * @param workerFile 宿主机上只读挂载的 worker 脚本
     * @param memoryLimit 内存上限
     * @param cpuLimit CPU 配额
     * @param processLimit 进程数上限
     * @param containerName 本执行器专属容器名称
     * @return 可直接传给 {@link ProcessBuilder} 的参数列表
     */
    static List<String> dockerCommand(
            String executable,
            String image,
            Path workerFile,
            String memoryLimit,
            double cpuLimit,
            int processLimit,
            String containerName
    ) {
        List<String> command = new ArrayList<>(List.of(
                executable,
                "run",
                "--rm",
                "--name=" + containerName,
                "--interactive",
                "--pull=never",
                "--network=none",
                "--read-only",
                "--pids-limit=" + processLimit,
                "--memory=" + memoryLimit,
                "--memory-swap=" + memoryLimit,
                "--cpus=" + cpuLimit,
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--user=65534:65534",
                "--tmpfs=/tmp:rw,noexec,nosuid,size=64m"
        ));
        command.add("--mount=type=bind,source=" + workerFile.toAbsolutePath()
                + ",target=" + WORKER_CONTAINER_PATH + ",readonly");
        command.add("--workdir=/tmp");
        command.add(image);
        command.addAll(List.of("python", "-I", "-u", WORKER_CONTAINER_PATH));
        return List.copyOf(command);
    }

    /**
     * 在 Docker daemon 中强制删除运行容器，避免 CLI 被取消后遗留后台容器。
     *
     * @param dockerExecutable Docker CLI 可执行文件
     * @param containerName 本执行器专属容器名称
     */
    private static void removeContainer(String dockerExecutable, String containerName) {
        Process cleanup = null;
        try {
            cleanup = new ProcessBuilder(dockerExecutable, "rm", "--force", containerName)
                    .redirectErrorStream(true)
                    .start();
            if (!cleanup.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                cleanup.destroyForcibly();
                throw new ExecutorException("Timed out while removing Python worker container");
            }
            String output = new String(cleanup.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (cleanup.exitValue() != 0 && !output.toLowerCase(java.util.Locale.ROOT)
                    .contains("no such container")) {
                throw new ExecutorException("Failed to remove Python worker container: " + output.strip());
            }
        } catch (IOException error) {
            throw new ExecutorException("Failed to start Docker container cleanup", error);
        } catch (InterruptedException error) {
            if (cleanup != null) {
                cleanup.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new ExecutorException("Interrupted while removing Python worker container", error);
        }
    }

    /**
     * 校验必须提供的命令或镜像名称。
     *
     * @param value 待校验值
     * @param name 参数名称
     */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new ConfigurationException(name + " must be non-blank and contain no control characters");
        }
    }
}
