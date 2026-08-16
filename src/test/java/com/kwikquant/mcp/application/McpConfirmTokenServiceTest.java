package com.kwikquant.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.infra.McpConfirmTokenInvalidException;
import org.junit.jupiter.api.Test;

/** {@link McpConfirmTokenService} 单测:签发/消费/过期/一次性/指纹绑定/跨用户隔离。 */
class McpConfirmTokenServiceTest {

    private static final String TOOL = "submit_order";
    private static final String PARAMS = "1|SPOT|BTC/USDT|BUY|MARKET|0.1";

    private static McpConfirmTokenService svc() {
        return new McpConfirmTokenService(120);
    }

    @Test
    void consume_matchingParams_succeeds() {
        var s = svc();
        var issue = s.issue(42L, TOOL, PARAMS);
        assertThat(issue.token()).isNotBlank();
        assertThat(issue.expiresInSec()).isEqualTo(120);
        // 消费不抛 = 通过
        s.consume(42L, TOOL, PARAMS, issue.token());
    }

    @Test
    void consume_twice_secondRejected() {
        var s = svc();
        var issue = s.issue(42L, TOOL, PARAMS);
        s.consume(42L, TOOL, PARAMS, issue.token());
        assertThatThrownBy(() -> s.consume(42L, TOOL, PARAMS, issue.token()))
                .isInstanceOf(McpConfirmTokenInvalidException.class);
    }

    @Test
    void consume_nullToken_throws() {
        var s = svc();
        assertThatThrownBy(() -> s.consume(42L, TOOL, PARAMS, null))
                .isInstanceOf(McpConfirmTokenInvalidException.class);
        assertThatThrownBy(() -> s.consume(42L, TOOL, PARAMS, "  "))
                .isInstanceOf(McpConfirmTokenInvalidException.class);
    }

    @Test
    void consume_unknownToken_throws() {
        assertThatThrownBy(() -> svc().consume(42L, TOOL, PARAMS, "no-such-token"))
                .isInstanceOf(McpConfirmTokenInvalidException.class);
    }

    @Test
    void consume_paramsChanged_tokenRejected() {
        var s = svc();
        var issue = s.issue(42L, TOOL, PARAMS);
        assertThatThrownBy(() -> s.consume(42L, TOOL, PARAMS + "|EXTRA", issue.token()))
                .isInstanceOf(McpConfirmTokenInvalidException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void consume_wrongTool_throws() {
        var s = svc();
        var issue = s.issue(42L, TOOL, PARAMS);
        assertThatThrownBy(() -> s.consume(42L, "cancel_order", PARAMS, issue.token()))
                .isInstanceOf(McpConfirmTokenInvalidException.class)
                .hasMessageContaining("different operation");
    }

    @Test
    void consume_crossUser_throws() {
        var s = svc();
        var issue = s.issue(42L, TOOL, PARAMS);
        // 跨用户令牌不可用(指纹含 userId)
        assertThatThrownBy(() -> s.consume(99L, TOOL, PARAMS, issue.token()))
                .isInstanceOf(McpConfirmTokenInvalidException.class);
    }

    @Test
    void purgeExpired_dropsStaleEntries() {
        var s = new McpConfirmTokenService(1);
        var issue = s.issue(42L, TOOL, PARAMS);
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThatThrownBy(() -> s.consume(42L, TOOL, PARAMS, issue.token()))
                .isInstanceOf(McpConfirmTokenInvalidException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void pendingCount_reflectsIssuedTokens() {
        var s = svc();
        s.issue(42L, TOOL, "a");
        s.issue(42L, TOOL, "b");
        assertThat(s.pendingCount()).isEqualTo(2);
    }

    @Test
    void issue_ttlEchoedFromConfig() {
        assertThat(svc().issue(1L, TOOL, "p").expiresInSec()).isEqualTo(120);
    }
}
