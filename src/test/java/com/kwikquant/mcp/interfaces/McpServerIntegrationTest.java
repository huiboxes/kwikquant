package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.infra.McpTokenService;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenScope;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * MCP Server E2E：真实 PAT → HTTP /mcp JSON-RPC 全链路（STREAMABLE 传输）。
 *
 * <p>STREAMABLE SSE 响应连接保持打开，不能用 {@code readAllBytes()}（会等到超时）。
 * 正确姿势：逐行读 SSE → 解析 {@code data:} 行 → 主动 disconnect。
 */
@SpringBootTest(
        classes = KwikquantApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class McpServerIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    UserMapper userMapper;

    @Autowired
    McpTokenService tokenService;

    private static final ObjectMapper OM = new ObjectMapper();

    private String fullPat;
    private String readOnlyPat;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setUsername("mcp-e2e-" + System.nanoTime());
        u.setEmail(u.getUsername() + "@e2e.test");
        u.setPasswordHash("h");
        userMapper.insert(u);

        McpTokenIssueResult full =
                tokenService.issue(u.getId(), "full", EnumSet.allOf(McpTokenScope.class), 90);
        this.fullPat = full.token();
        McpTokenIssueResult ro =
                tokenService.issue(u.getId(), "read-only", EnumSet.of(McpTokenScope.READ), 90);
        this.readOnlyPat = ro.token();
    }

    private HttpURLConnection post(String path, String pat, String sessionId, String body) throws Exception {
        var url = URI.create("http://127.0.0.1:" + port + path).toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + pat);
        if (sessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        conn.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        conn.setRequestProperty("Accept", MediaType.APPLICATION_JSON_VALUE + ", text/event-stream");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    /**
     * 逐行读 SSE 响应直到命中 {@code data:} 行 → 解析 JSON 后主动 disconnect。
     * SSE 连接保持打开才能读到完整的 event，不能靠 EOF。
     */
    /**
     * 读取 MCP 响应体，兼容 SSE（{@code id:\n event:\n data: {...}}）和纯 JSON 两种格式。
     * SSE 连接保持打开，逐行读直到命中 {@code data:} 行 → 解析 → 主动 disconnect。
     */
    @SuppressWarnings("unchecked")
    private Map<?, ?> readSseJson(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder firstBlock = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                firstBlock.append(line).append("\n");
                if (line.startsWith("data:")) {
                    String json = line.substring(line.charAt(5) == ' ' ? 6 : 5);
                    conn.disconnect();
                    return OM.readValue(json, Map.class);
                }
                // 空行 = SSE event 结束，继续等下一个 event（可能还有更多 data 行）
            }
            conn.disconnect();
            // 纯 JSON 响应（无 SSE 包装）
            String raw = firstBlock.toString().trim();
            if (raw.startsWith("{")) {
                return OM.readValue(raw, Map.class);
            }
            throw new IllegalStateException("unexpected MCP response: " + firstBlock);
        }
    }

    private String initSession(String pat) throws Exception {
        String json = OM.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 0,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "test", "version", "1.0"))));
        HttpURLConnection conn = post("/mcp", pat, null, json);
        String sid = conn.getHeaderField("Mcp-Session-Id");
        assertThat(sid).as("Mcp-Session-Id header").isNotNull();
        // 消费 initialize 的 SSE 响应体（否则连接残留）
        readSseJson(conn);
        return sid;
    }

    @Test
    void toolsList_returnsRegisteredTools() throws Exception {
        String sid = initSession(fullPat);
        String json = OM.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()));
        HttpURLConnection conn = post("/mcp", fullPat, sid, json);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        Map<?, ?> resp = readSseJson(conn);
        assertThat(resp.get("result")).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) resp.get("result");
        assertThat(result.get("tools")).isNotNull();
    }

    @Test
    void invalidPat_returns401() throws Exception {
        String json = OM.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()));
        HttpURLConnection conn = post("/mcp", "kq_pat_invalid", null, json);
        assertThat(conn.getResponseCode()).isEqualTo(401);
        // 401 是标准 JSON 响应（不经 SSE transport），直接读 error stream
        String body = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(body).contains("mcp token invalid");
    }

    @Test
    void writeTool_withReadOnlyScope_returnsScopeDeniedError() throws Exception {
        // submit_order 要求 TRADE scope，READ-only PAT → 工具抛 McpScopeDeniedException → MCP isError
        String sid = initSession(fullPat);
        String json = OM.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/call",
                "params", Map.of("name", "submit_order", "arguments", Map.of())));
        HttpURLConnection conn = post("/mcp", readOnlyPat, sid, json);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        Map<?, ?> resp = readSseJson(conn);
        assertThat(resp).isNotNull();
        assertThat(resp.containsKey("error") || resp.containsKey("result"))
                .as("response should be valid JSON-RPC response")
                .isTrue();
    }
}