package com.kwikquant.ai.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kwikquant.ai.application.RiskPolicyParseRequest;
import com.kwikquant.ai.application.RiskPolicyParseService;
import com.kwikquant.risk.domain.RiskRuleType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link AiRiskPolicyController} — 解析预览视图映射 + currentUserId 透传(H6)。
 */
class AiRiskPolicyControllerTest {

    private RiskPolicyParseService parseService;
    private AiRiskPolicyController controller;

    @BeforeEach
    void setUp() {
        parseService = mock(RiskPolicyParseService.class);
        controller = new AiRiskPolicyController(parseService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void parse_mapsResultToView_andPropagatesUserId() {
        RiskPolicyParseRequest req = new RiskPolicyParseRequest(1L, "单笔不超过 5000", null);
        RiskPolicyParseService.ParseResult result = new RiskPolicyParseService.ParseResult(
                "单笔不超过 5000 USDT",
                List.of(new RiskPolicyParseService.ParsedRule(
                        RiskRuleType.MAX_NOTIONAL, "单笔上限", Map.of("maxNotionalUsdt", "5000"))));
        when(parseService.parse(any(), eq(42L))).thenReturn(result);

        var response = controller.parse(req);

        assertThat(response.data().summary()).contains("5000");
        assertThat(response.data().rules()).hasSize(1);
        assertThat(response.data().rules().get(0).ruleType()).isEqualTo("MAX_NOTIONAL");
        assertThat(response.data().rules().get(0).params()).containsEntry("maxNotionalUsdt", "5000");
        // H6: currentUserId(42)透传给服务层做 key 归属校验
        verify(parseService).parse(req, 42L);
    }

    @Test
    void parse_multipleRules_preserveOrder() {
        RiskPolicyParseRequest req = new RiskPolicyParseRequest(1L, "x", null);
        RiskPolicyParseService.ParseResult result = new RiskPolicyParseService.ParseResult(
                "s",
                List.of(
                        new RiskPolicyParseService.ParsedRule(
                                RiskRuleType.MAX_NOTIONAL, "A", Map.of("maxNotionalUsdt", "1")),
                        new RiskPolicyParseService.ParsedRule(
                                RiskRuleType.ORDER_FREQUENCY, "B", Map.of("maxPerMinute", "2"))));
        when(parseService.parse(any(), eq(42L))).thenReturn(result);

        var response = controller.parse(req);

        assertThat(response.data().rules().get(0).name()).isEqualTo("A");
        assertThat(response.data().rules().get(1).name()).isEqualTo("B");
    }
}
