package org.fzb.smolagents.executor;

import org.fzb.smolagents.core.CancellationToken;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Docker Python 执行器的隔离参数和配置边界。
 */
class DockerPythonCodeExecutorTest {
    /**
     * 验证容器禁止网络、限制资源并仅只读挂载 worker 脚本。
     */
    @Test
    void buildsRestrictedContainerCommand() {
        List<String> command = DockerPythonCodeExecutor.dockerCommand(
                "docker",
                "python:3.12-slim",
                Path.of("C:\\temp\\worker.py"),
                "512m",
                1.0,
                32,
                "smolagents-test"
        );

        assertEquals("docker", command.getFirst());
        assertTrue(command.contains("--pull=never"));
        assertTrue(command.contains("--network=none"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--cap-drop=ALL"));
        assertTrue(command.contains("--security-opt=no-new-privileges"));
        assertTrue(command.contains("--user=65534:65534"));
        assertTrue(command.contains("--pids-limit=32"));
        assertTrue(command.contains("--memory=512m"));
        assertTrue(command.contains("--memory-swap=512m"));
        assertTrue(command.contains("--name=smolagents-test"));
        assertTrue(command.contains("--cpus=1.0"));
        assertTrue(command.stream().anyMatch(arg ->
                arg.startsWith("--mount=type=bind,source=")
                        && arg.endsWith(",target=/opt/smolagents/python_worker.py,readonly")
        ));
        assertEquals("python", command.get(command.size() - 4));
        assertEquals("-I", command.get(command.size() - 3));
        assertEquals("-u", command.get(command.size() - 2));
        assertEquals("/opt/smolagents/python_worker.py", command.getLast());
    }

    /**
     * 验证无效资源限制在执行前被拒绝。
     */
    @Test
    void rejectsInvalidContainerLimits() {
        assertThrows(IllegalArgumentException.class, () -> new DockerPythonCodeExecutor(
                "docker",
                "python:3.12-slim",
                Duration.ofSeconds(1),
                1000,
                List.of(),
                "0m",
                1.0,
                16
        ));
        assertThrows(IllegalArgumentException.class, () -> new DockerPythonCodeExecutor(
                "docker",
                "python:3.12-slim",
                Duration.ofSeconds(1),
                1000,
                List.of(),
                "512m",
                Double.NaN,
                16
        ));
    }

    /**
     * 在显式启用且预先准备好 Docker 镜像时验证真实容器执行。
     */
    @Test
    void executesInsideDockerWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(
                "1".equals(System.getenv("SMOLAGENTS_DOCKER_TESTS")),
                "Set SMOLAGENTS_DOCKER_TESTS=1 to run the Docker integration test"
        );
        String docker = System.getProperty("smolagents.docker", "docker");
        String image = System.getProperty("smolagents.docker.image", "python:3.12-slim");
        try (DockerPythonCodeExecutor executor = new DockerPythonCodeExecutor(docker, image)) {
            ExecutorResult result = executor.execute(new ExecutorRequest(
                    "6 * 7",
                    Map.of(),
                    Map.of(),
                    Duration.ofSeconds(20),
                    new CancellationToken()
            ));

            assertTrue(result.succeeded());
            assertEquals(42, result.value().structuredContent().asInt());
        }
    }
}
