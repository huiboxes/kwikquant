package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.risk.application.RiskService;
import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleResult;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.OrderMetricsService;
import com.kwikquant.trading.domain.InvalidOrderException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 单测 {@link RiskDryRunController}：ownership 链（越权 404 防探测）、verdict 与中间指标回传、
 * requestId 前缀 {@code dryrun-}、且只调 {@link RiskService#evaluate} 不调 {@link RiskService#check}
 * （即不持久化 decision，dry-run 无副作用的核心保证）。
 */
class RiskDryRunControllerTest {

    private ExchangeAccountService accountService;
    private OrderMetricsService orderMetrics;
    private RiskService riskService;
    private RiskDryRunController controller;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        orderMetrics = mock(OrderMetricsService.class);
        riskService = mock(RiskService.class);
        controller = new RiskDryRunController(accountService, orderMetrics, riskService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static RiskDryRunRequest req(OrderType type, BigDecimal price) {
        return new RiskDryRunRequest(
                7L, "BTC/USDT", OrderSide.BUY, type, new BigDecimal("0.1"), price, MarketType.SPOT);
    }

    @Test
    void dryRun_whenOwner_returnsVerdictAndMetrics() {
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        when(orderMetrics.resolveMarketPrice(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("50000"));
        when(orderMetrics.notional(any(), any(), any())).thenReturn(new BigDecimal("5000"));
        when(orderMetrics.countRecentOrders(7L)).thenReturn(1);
        when(orderMetrics.previewRecentOrderCount(7L)).thenReturn(2);
        when(orderMetrics.dailyRealizedPnl(7L)).thenReturn(new BigDecimal("-120"));
        RiskDecision d = new RiskDecision();
        d.setVerdict(RiskVerdict.APPROVED);
        d.setRuleResults(List.of());
        when(riskService.evaluate(any(RiskCheckRequest.class))).thenReturn(d);

        var resp = controller.dryRun(req(OrderType.LIMIT, new BigDecimal("42000")));

        assertThat(resp.data().verdict()).isEqualTo(RiskVerdict.APPROVED);
        assertThat(resp.data().notionalValue()).isEqualByComparingTo("5000");
        assertThat(resp.data().recentOrderCount()).isEqualTo(2);
        assertThat(resp.data().dailyRealizedPnl()).isEqualByComparingTo("-120");
        // 关键：dry-run 只调 evaluate（无副作用），绝不调 check（会持久化 decision）
        verify(riskService).evaluate(any(RiskCheckRequest.class));
        verify(riskService, never()).check(any(RiskCheckRequest.class));
    }

    @Test
    void dryRun_passesDryRunPrefixedRequestIdAndZeroOrderId() {
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        when(orderMetrics.notional(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(orderMetrics.countRecentOrders(anyLong())).thenReturn(0);
        when(orderMetrics.previewRecentOrderCount(anyLong())).thenReturn(0);
        when(orderMetrics.dailyRealizedPnl(anyLong())).thenReturn(BigDecimal.ZERO);
        RiskDecision d = new RiskDecision();
        d.setVerdict(RiskVerdict.REJECTED);
        d.setRuleResults(List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "exceeds")));
        when(riskService.evaluate(any(RiskCheckRequest.class))).thenReturn(d);

        controller.dryRun(req(OrderType.MARKET, null));

        ArgumentCaptor<RiskCheckRequest> captor = ArgumentCaptor.forClass(RiskCheckRequest.class);
        verify(riskService).evaluate(captor.capture());
        assertThat(captor.getValue().requestId()).startsWith("dryrun-");
        assertThat(captor.getValue().orderId()).isZero();
    }

    @Test
    void dryRun_whenNotOwner_throwsResourceNotFoundToPreventProbing() {
        when(accountService.getOwned(7L, 42L)).thenThrow(new AccessDeniedException("not yours"));

        assertThatThrownBy(() -> controller.dryRun(req(OrderType.LIMIT, new BigDecimal("42000"))))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(riskService, never()).evaluate(any());
    }

    @Test
    void dryRun_staleMarketBuy_throwsInvalidOrderExceptionMirroringSubmit() {
        // MARKET BUY + 无市价（resolveMarketPrice 返 null）→ 镜像 submit 的 fail-fast 守卫抛
        // InvalidOrderException，交 TradingExceptionHandler。不调 evaluate（无 false-APPROVE）。
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        // resolveMarketPrice 未 stub → 默认返 null（模拟 stale 行情）

        assertThatThrownBy(() -> controller.dryRun(req(OrderType.MARKET, null)))
                .isInstanceOf(InvalidOrderException.class);
        verify(riskService, never()).evaluate(any());
    }
}
