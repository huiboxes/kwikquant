package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * DockerWorkerManager.healthCheck HTTP 契约测试(§3.7 healthCheck HTTP)。
 *
 * <p>createAndStart/stop/remove 依赖 docker daemon,单元测试不覆盖(JaCoCo 排除,集成测试
 * 在 Wave 8 Worker 镜像就绪后补)。healthCheck 走 java.net.http,可 mock HttpClient 覆盖。
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
}
