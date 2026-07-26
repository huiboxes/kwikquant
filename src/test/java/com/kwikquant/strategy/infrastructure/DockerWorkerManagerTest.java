package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * DockerWorkerManager.healthCheck HTTP 契约测试。
 *
 * <p>createAndStart/stop/remove 依赖 docker daemon,单元测试不覆盖(JaCoCo 排除,集成测试
 * 在 Worker 镜像就绪后补)。healthCheck 走 java.net.http,可 mock HttpClient 覆盖。
 */
class DockerWorkerManagerTest {

    @Test
    void healthCheck_httpGet200_returnsTrue() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = (HttpResponse<Void>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp);

        DockerWorkerManager mgr = new DockerWorkerManager(client, "");
        assertThat(mgr.healthCheck("strategy-worker-1")).isTrue();
    }

    @Test
    void healthCheck_httpGet500_returnsFalse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = (HttpResponse<Void>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp);

        DockerWorkerManager mgr = new DockerWorkerManager(client, "");
        assertThat(mgr.healthCheck("strategy-worker-1")).isFalse();
    }

    @Test
    void healthCheck_ioException_returnsFalse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection refused"));

        DockerWorkerManager mgr = new DockerWorkerManager(client, "");
        assertThat(mgr.healthCheck("strategy-worker-1")).isFalse();
    }

    @Test
    void healthCheck_interruptedException_returnsFalse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("interrupted"));

        DockerWorkerManager mgr = new DockerWorkerManager(client, "");
        assertThat(mgr.healthCheck("strategy-worker-1")).isFalse();
    }

    @Test
    void healthCheck_usesContainerNameAsHost_defaultOverride() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = (HttpResponse<Void>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(204);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    URI uri = req.uri();
                    assertThat(uri.getScheme()).isEqualTo("http");
                    assertThat(uri.getHost()).isEqualTo("strategy-worker-42");
                    assertThat(uri.getPort()).isEqualTo(8081);
                    assertThat(uri.getPath()).isEqualTo("/health");
                    return resp;
                });

        DockerWorkerManager mgr = new DockerWorkerManager(client, "");
        assertThat(mgr.healthCheck("strategy-worker-42")).isTrue();
    }

    @Test
    void healthCheck_hostOverrideAppliedForLocalTesting() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = (HttpResponse<Void>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(0);
                    assertThat(req.uri().getHost()).isEqualTo("localhost");
                    return resp;
                });

        DockerWorkerManager mgr = new DockerWorkerManager(client, "localhost");
        assertThat(mgr.healthCheck("strategy-worker-42")).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 以下为 JaCoCo 预存债补测(2026-07-22):
    // - <init>(HttpClient, String) line=54 的 healthHostOverride == null 三元分支
    // - healthCheck line=136 的 statusCode < 300 false 分支(边界 300,500 会短路)
    // - sanitizeName line=166 的 null / 非 null 两分支(private static,反射覆盖)
    // createAndStart/stop/remove/isRunning/runQuiet/runCapture 直依赖 ProcessBuilder
    // 不可注入,无 docker daemon 不能稳定单测 → 待补齐。
    // ──────────────────────────────────────────────────────────────────────────────

    /** 覆盖 <init> line=54 的 null 分支:healthHostOverride 传 null 应被规整为空串。 */
    @Test
    void constructor_nullHealthHostOverride_treatedAsEmpty() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = (HttpResponse<Void>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    // host 应是 containerId 而非字符串 "null",证明 null 被规整为 ""
                    assertThat(inv.getArgument(0, HttpRequest.class).uri().getHost())
                            .isEqualTo("strategy-worker-7");
                    return resp;
                });

        // noinspection resource:测试无需 close,HttpClient 是 mock
        DockerWorkerManager mgr = new DockerWorkerManager(client, null);
        assertThat(mgr.healthCheck("strategy-worker-7")).isTrue();
    }

    /** 覆盖 healthCheck 的 statusCode < 300 false 分支:边界 300(>= 200 true, < 300 false)。 */
    @Test
    void healthCheck_statusCode300_returnsFalse() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = (HttpResponse<Void>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(300);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp);

        DockerWorkerManager mgr = new DockerWorkerManager(client, "");
        assertThat(mgr.healthCheck("strategy-worker-1")).isFalse();
    }

    /** 边界对照:299 应判为 healthy(< 300 true 分支)。 */
    @Test
    void healthCheck_statusCode299_returnsTrue() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> resp = (HttpResponse<Void>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(299);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resp);

        DockerWorkerManager mgr = new DockerWorkerManager(client, "");
        assertThat(mgr.healthCheck("strategy-worker-1")).isTrue();
    }

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
