package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.infra.McpTokenService;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP Server E2E:真实 PAT → HTTP /mcp JSON-RPC 全链路(此前零集成覆盖)。
 * 验证 @McpTool 注册(tools/list)、PAT 鉴权(filter→SecurityContext)、scope 拒绝(10005)、
 * 读工具调用、无效 token 401。真实 HTTP 请求需要真实 servlet 容器 → RANDOM_PORT
 * (基类默认 MOCK 不起服务器,@LocalServerPort 注入会失败)。数据源走基类 TestDatabase
 * 双路(Testcontainers / KQ_TEST_DB_URL 外部库),与 CI 和受限沙箱均兼容。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    UserMapper userMapper;

    @Autowired
    McpTokenService tokenService;

    private String fullPat;
    private String readOnlyPat;
    private RestClient client;

    private static final ObjectMapper OM = new ObjectMapper();

    /** PAT → 已握手会话 id（MCP Streamable 有状态模式，每个 PAT 握手一次）。 */
    private final Map<String, String> sessions = new HashMap<>();

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setUsername("mcp-e2e-" + System.nanoTime());
        u.setEmail(u.getUsername() + "@e2e.test");
        u.setPasswordHash("h");
        userMapper.insert(u);

        McpTokenIssueResult full = tokenService.issue(u.getId(), "full", EnumSet.allOf(McpTokenScope.class), 90);
        this.fullPat = full.token();
        McpTokenIssueResult ro = tokenService.issue(u.getId(), "read-only", EnumSet.of(McpTokenScope.READ), 90);
        this.readOnlyPat = ro.token();

        // 4xx/5x 不抛异常：invalidPat_returns401 需要断言状态码本身（RestClient 默认对错误码抛异常）。
        // 显式 SimpleClientHttpRequestFactory：classpath 有 reactor-netty 时 RestClient 默认用它做
        // 连接池，notification 的 202 空响应会留下半关连接被下个请求复用 → PrematureCloseException。
        client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @Test
    void toolsList_returnsRegisteredTools() {
        Map<?, ?> resp = call(fullPat, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()));
        assertThat(resp.get("result")).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) resp.get("result");
        assertThat(result.get("tools")).isNotNull();
    }

    @Test
    void invalidPat_returns401() {
        ResponseEntity<String> resp = client.post()
                .uri("/mcp")
                .header("Authorization", "Bearer kq_pat_invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()))
                .retrieve()
                .toEntity(String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void writeTool_withReadOnlyScope_returnsScopeDeniedError() {
        // submit_order 要求 TRADE,READ-only PAT → 工具抛 McpScopeDeniedException → MCP isError
        // arguments 必须过 JSON schema 校验（必填项齐全），才会进到方法体内的 scopeGuard；
        // scope 拒绝先于任何业务逻辑，accountId 无需真实存在
        Map<?, ?> resp = call(
                readOnlyPat,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        2,
                        "method",
                        "tools/call",
                        "params",
                        Map.of(
                                "name",
                                "submit_order",
                                "arguments",
                                Map.of(
                                        "accountId",
                                        999_999,
                                        "marketType",
                                        "spot",
                                        "symbol",
                                        "BTC/USDT",
                                        "side",
                                        "buy",
                                        "orderType",
                                        "market",
                                        "amount",
                                        1))));
        // MCP 协议:工具异常映射为 {result:{isError:true, content:[{type:text,text:<message>}]}}
        Map<?, ?> result = (Map<?, ?>) resp.get("result");
        assertThat(result).isNotNull();
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(String.valueOf(result.get("content"))).containsIgnoringCase("scope insufficient");
    }

    private Map<?, ?> call(String pat, Map<String, Object> body) {
        // MCP Streamable transport 要求 Accept 同时含 text/event-stream 与 application/json（否则 -32601）;
        // 有状态模式下 tools/* 还必须携带经 initialize 建立的 Mcp-Session-Id。
        // 服务端对 tools/* 走 SSE 且发完即掐连接（chunked 流被截断），HttpMessageConverter 体系
        // （Map/String 均）读不了这种响应 → exchange 手动攒字节，读中断时保留已读部分再解析。
        String bodyText = client.post()
                .uri("/mcp")
                .header("Authorization", "Bearer " + pat)
                .header("Mcp-Session-Id", sessions.computeIfAbsent(pat, this::initialize))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (InputStream in = response.getBody()) {
                        byte[] chunk = new byte[8192];
                        while (true) {
                            int read;
                            try {
                                read = in.read(chunk);
                            } catch (IOException truncatedSseStream) {
                                break; // SSE 发完掐连接：已读帧完整即可解析
                            }
                            if (read == -1) {
                                break;
                            }
                            buffer.write(chunk, 0, read);
                        }
                    }
                    return buffer.toString(StandardCharsets.UTF_8);
                });
        return parseMcpResponse(bodyText);
    }

    /** 响应两种形态：application/json 直出，或 SSE 流（取首个 {@code data:} 帧的 JSON-RPC 报文）。 */
    @SuppressWarnings("unchecked")
    private static Map<?, ?> parseMcpResponse(String bodyText) {
        assertThat(bodyText).as("MCP 响应体").isNotNull();
        String json = bodyText;
        int dataIndex = bodyText.indexOf("data:");
        if (dataIndex >= 0) {
            String firstFrame = bodyText.substring(dataIndex + "data:".length());
            int lineEnd = firstFrame.indexOf('\n');
            json = (lineEnd >= 0 ? firstFrame.substring(0, lineEnd) : firstFrame).strip();
        }
        try {
            return OM.readValue(json, Map.class);
        } catch (Exception e) {
            throw new AssertionError("无法解析 MCP 响应 JSON: " + json, e);
        }
    }

    /** MCP Streamable 有状态协议握手:initialize → 取 Mcp-Session-Id → notifications/initialized。 */
    @SuppressWarnings("unchecked")
    private String initialize(String pat) {
        ResponseEntity<Map> init = client.post()
                .uri("/mcp")
                .header("Authorization", "Bearer " + pat)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        0,
                        "method",
                        "initialize",
                        "params",
                        Map.of(
                                "protocolVersion",
                                "2025-03-26",
                                "capabilities",
                                Map.of(),
                                "clientInfo",
                                Map.of("name", "e2e-test", "version", "1.0"))))
                .retrieve()
                .toEntity(Map.class);
        String session = init.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(session).as("initialize 必须返回 Mcp-Session-Id").isNotNull();

        client.post()
                .uri("/mcp")
                .header("Authorization", "Bearer " + pat)
                .header("Mcp-Session-Id", session)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                .retrieve()
                .toBodilessEntity();
        return session;
    }
}
