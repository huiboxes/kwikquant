package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.strategy.application.SubprocessResult;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RealSubprocessExecutorTest {

    @Test
    void run_exposesOnlyRuntimeAndExplicitEnvironmentWhitelist() {
        SubprocessResult result = new RealSubprocessExecutor()
                .run(List.of("/usr/bin/env"), Map.of("KWIKQUANT_TEST_MARKER", "available"), null, 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        Map<String, String> childEnv = Arrays.stream(result.stdout().lines().toArray(String[]::new))
                .map(line -> line.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0], parts -> parts.length == 2 ? parts[1] : ""));
        assertThat(childEnv.keySet())
                .isEqualTo(Set.of("PATH", "PYTHONPATH", "LANG", "LC_ALL", "KWIKQUANT_TEST_MARKER"));
        assertThat(childEnv.get("KWIKQUANT_TEST_MARKER")).isEqualTo("available");
        assertThat(childEnv)
                .allSatisfy((name, value) -> assertThat(value).as(name).isNotBlank());
        assertThat(childEnv.keySet()).noneMatch(name -> name.matches(".*(SECRET|JWT|DB|DATABASE|POSTGRES).*"));
    }

    @Test
    void run_writesStdinPayloadAndCloses() {
        // docker run -i 配置下发通道:payload 写入后关闭 stdin,子进程读到 EOF 正常结束
        SubprocessResult result =
                new RealSubprocessExecutor().run(List.of("/bin/cat"), Map.of(), "task-config-json", 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("task-config-json");
    }
}
