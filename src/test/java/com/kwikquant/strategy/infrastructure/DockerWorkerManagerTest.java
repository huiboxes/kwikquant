package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * DockerWorkerManager 单测。
 *
 * <p>createAndStart/stop/remove/isRunning/healthCheck 直依赖 docker daemon,单元测试不覆盖
 * (JaCoCo 排除,集成测试在 Worker 镜像就绪后补)。healthCheck 改 docker inspect isRunning 后
 * 不再依赖 HTTP 网络(host 进程解析不了 docker network 内部名字),但 isRunning 仍依赖 daemon,
 * 待补齐。sanitizeName 是纯函数,反射覆盖。
 */
class DockerWorkerManagerTest {

    /** 覆盖 sanitizeName line=166 的 null 分支:返回空串(防 NPE + 防注入)。 */
    @Test
    void sanitizeName_nullInput_returnsEmpty() throws Exception {
        assertThat(invokeSanitizeName(null)).isEqualTo("");
    }

    /** 覆盖 sanitizeName 非 null 分支:非法字符(如 shell 元字符 / 引号)被剥离。 */
    @Test
    void sanitizeName_stripsIllegalCharacters() throws Exception {
        // 正则 [^a-zA-Z0-9_\-\s]:字母/数字/下划线/中划线/空白 保留,其余剥离
        // 本例剥离: ' ; ` $ . /  → 余字母
        String result = invokeSanitizeName("RSI;ETH'rm`$x/..");
        assertThat(result).isEqualTo("RSIETHrmx");
        assertThat(result)
                .doesNotContain("'")
                .doesNotContain(";")
                .doesNotContain("`")
                .doesNotContain("$")
                .doesNotContain("/")
                .doesNotContain(".");
    }

    /** 覆盖 sanitizeName 非 null 分支:合法字符(字母/数字/下划线/中划线/空白)原样保留。 */
    @Test
    void sanitizeName_keepsValidCharacters_unmodified() throws Exception {
        String valid = "RSI-ETH_5m cross  42";
        assertThat(invokeSanitizeName(valid)).isEqualTo(valid);
    }

    /** 反射调用 private static sanitizeName,绕开 createAndStart 的 docker daemon 依赖。 */
    private static String invokeSanitizeName(String input) throws Exception {
        Method m = DockerWorkerManager.class.getDeclaredMethod("sanitizeName", String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, input);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? (Exception) e.getCause() : e;
        }
    }
}
