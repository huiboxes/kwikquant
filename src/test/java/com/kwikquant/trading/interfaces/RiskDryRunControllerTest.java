package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
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
import java.util.Map;
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
    private BalanceService balanceService;
    private RiskDryRunController controller;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        orderMetrics = mock(OrderMetricsService.class);
        riskService = mock(RiskService.class);
        balanceService = mock(BalanceService.class);
        controller = new RiskDryRunController(accountService, orderMetrics, riskService, balanceService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static RiskDryRunRequest req(OrderType type, BigDecimal price) {
        return new RiskDryRunRequest(
                7L, "BTC/USDT", OrderSide.BUY, type, new BigDecimal("0.1"), price, MarketType.SPOT, null);
    }

    /** PERP dry-run fixture:LIMIT 单 + leverage 10,触发 PERP 余额查询路径。 */
    private static RiskDryRunRequest perpReq(Integer leverage) {
        return new RiskDryRunRequest(
                7L,
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.LIMIT,
                new BigDecimal("0.1"),
                new BigDecimal("42000"),
                MarketType.PERP,
                leverage);
    }

    @Test
    void dryRun_whenOwner_returnsVerdictAndMetrics() {
        when(accountService.getOwned(7L, 42L)).thenReturn(new ExchangeAccount());
        when(orderMetrics.resolveMarketPrice(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("50000"));
        when(orderMetrics.notional(any(), any(), any())).thenReturn(new BigDecimal("5000"));
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
        when(orderMetrics.previewRecentOrderCount(anyLong())).thenReturn(0);
        when(orderMetrics.dailyRealizedPnl(anyLong())).thenReturn(BigDecimal.ZERO);
        RiskDecision d = new RiskDecision();
        d.setVerdict(RiskVerdict.REJECTED);
        d.setRuleResults(List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "exceeds")));
        when(riskService.evaluate(any(RiskCheckRequest.class))).thenReturn(d);

        // 用 LIMIT 走干净路径（不触发 MARKET BUY 守卫），聚焦验证 requestId 前缀与 orderId=0
        controller.dryRun(req(OrderType.LIMIT, new BigDecimal("42000")));

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
        when(orderMetrics.resolveMarketPrice(any(), any(), any(), any(), any())).thenReturn(null);
        when(orderMetrics.marketBuyLacksPrice(OrderType.MARKET, OrderSide.BUY, null))
                .thenReturn(true);

        assertThatThrownBy(() -> controller.dryRun(req(OrderType.MARKET, null)))
                .isInstanceOf(InvalidOrderException.class);
        verify(riskService, never()).evaluate(any());
    }

    @Test
    void dryRun_perp_fillsAvailableMarginFromSwapBalance() {
        // PERP dry-run 必须走 swap 余额(三参 PERP fetchBalance)填 availableMargin/totalBalance,
        // 否则 MaxInitialMarginEvaluator fail-closed 拒(原 bug:leverage/余额全 null)。
        ExchangeAccount okx = new ExchangeAccount();
        when(accountService.getOwned(7L, 42L)).thenReturn(okx);
        when(orderMetrics.resolveMarketPrice(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("50000"));
        when(orderMetrics.notional(any(), any(), any())).thenReturn(new BigDecimal("5000"));
        when(orderMetrics.previewRecentOrderCount(7L)).thenReturn(2);
        when(orderMetrics.dailyRealizedPnl(7L)).thenReturn(new BigDecimal("-120"));
        // 模拟 swap 账户余额:USDT free=可用保证金=3000,total=总权益=5000
        BalanceSnapshot snap = new BalanceSnapshot(Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(
                        new BigDecimal("3000"), new BigDecimal("2000"), new BigDecimal("5000"))));
        when(balanceService.fetchBalance(7L, 42L, MarketType.PERP)).thenReturn(snap);
        RiskDecision d = new RiskDecision();
        d.setVerdict(RiskVerdict.APPROVED);
        d.setRuleResults(List.of());
        when(riskService.evaluate(any(RiskCheckRequest.class))).thenReturn(d);

        controller.dryRun(perpReq(10));

        ArgumentCaptor<RiskCheckRequest> captor = ArgumentCaptor.forClass(RiskCheckRequest.class);
        verify(riskService).evaluate(captor.capture());
        RiskCheckRequest passed = captor.getValue();
        assertThat(passed.marketType()).isEqualTo(MarketType.PERP);
        assertThat(passed.leverage()).isEqualTo(10);
        assertThat(passed.availableMargin()).isEqualByComparingTo("3000");
        assertThat(passed.totalBalance()).isEqualByComparingTo("5000");
        // 关键:PERP 走 swap 余额(三参 PERP),绝不走 SPOT(现货余额会致风控估值失真)
        verify(balanceService).fetchBalance(7L, 42L, MarketType.PERP);
        verify(balanceService, never()).fetchBalance(eq(7L), eq(42L), eq(MarketType.SPOT));
    }

    @Test
    void dryRun_perp_fetchBalanceFails_leavesNullAndFailClosedByEvaluator() {
        // fetchBalance 失败(网络/交易所异常) → availableMargin/totalBalance=null
        // → MaxInitialMarginEvaluator fail-closed 拒(与 submit 无余额时一致,faithfulness 保持)
        ExchangeAccount okx = new ExchangeAccount();
        when(accountService.getOwned(7L, 42L)).thenReturn(okx);
        when(orderMetrics.resolveMarketPrice(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("50000"));
        when(orderMetrics.notional(any(), any(), any())).thenReturn(new BigDecimal("5000"));
        when(orderMetrics.previewRecentOrderCount(7L)).thenReturn(2);
        when(orderMetrics.dailyRealizedPnl(7L)).thenReturn(BigDecimal.ZERO);
        when(balanceService.fetchBalance(7L, 42L, MarketType.PERP)).thenThrow(new RuntimeException("okx 5xx"));
        RiskDecision d = new RiskDecision();
        d.setVerdict(RiskVerdict.REJECTED);
        d.setRuleResults(List.of());
        when(riskService.evaluate(any(RiskCheckRequest.class))).thenReturn(d);

        controller.dryRun(perpReq(10));

        ArgumentCaptor<RiskCheckRequest> captor = ArgumentCaptor.forClass(RiskCheckRequest.class);
        verify(riskService).evaluate(captor.capture());
        assertThat(captor.getValue().availableMargin()).isNull();
        assertThat(captor.getValue().totalBalance()).isNull();
    }
}
