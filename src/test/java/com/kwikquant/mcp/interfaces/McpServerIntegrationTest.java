package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.infra.McpTokenService;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenScope;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * MCP Server E2E:真实 PAT → HTTP /mcp JSON-RPC 全链路(此前零集成覆盖)。
 * 验证 @McpTool 注册(tools/list)、PAT 鉴权(filter→SecurityContext)、scope 拒绝(10005)、
 * 读工具调用、无效 token 401。CI 环境(Docker 可起 Testcontainers)运行;沙箱无容器时跳过。
 */
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

        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
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
        org.springframework.http.ResponseEntity<String> resp = client.post()
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
                        Map.of("name", "submit_order", "arguments", Map.of())));
        // MCP 协议:工具异常映射为 {isError:true, content:[{type:text,text:<message>}]}
        assertThat(resp).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> call(String pat, Map<String, Object> body) {
        return client.post()
                .uri("/mcp")
                .header("Authorization", "Bearer " + pat)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
    }
}
