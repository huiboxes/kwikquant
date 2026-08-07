package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.mcp.interfaces.view.FundingSettlementView;
import com.kwikquant.mcp.interfaces.view.LiquidationView;
import com.kwikquant.mcp.interfaces.view.OrderView;
import com.kwikquant.mcp.interfaces.view.PositionView;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.application.FundingSettlementService;
import com.kwikquant.trading.application.LiquidationService;
import com.kwikquant.trading.application.OrderCancelResult;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionEnricher;
import com.kwikquant.trading.application.PositionEnrichment;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.FundingSettlement;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link TradingTools} 单测。验证:7 个 @McpTool 的 service 委托、OrderView/PositionView 投影、
 * submitOrder SPOT/PERP 分流 + PERP 必填校验、closePosition 委托 TradingService.closePosition +
 * 风控拒绝 catch 转 RISK_REJECTED、getPositions/getOpenOrders/get_funding_history/get_liquidation_history
 * 前置 getOwned 校验(不属用户抛 1002)、limit 截断 200、404 异常冒出(下沉 service 抛,工具层不吞)。
 */
class TradingToolsTest {

    private TradingService tradingService;
    private PositionService positionService;
    private ExchangeAccountService accountService;
    private PositionEnricher positionEnricher;
    private FundingSettlementService fundingSettlementService;
    private LiquidationService liquidationService;
    private TradingTools tools;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        positionService = mock(PositionService.class);
        accountService = mock(ExchangeAccountService.class);
        positionEnricher = mock(PositionEnricher.class);
        fundingSettlementService = mock(FundingSettlementService.class);
        liquidationService = mock(LiquidationService.class);
        tools = new TradingTools(
                tradingService,
                positionService,
                accountService,
                positionEnricher,
                fundingSettlementService,
                liquidationService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── submit_order ──

    @Test
    void submitOrder_spotMarketBuy_shouldDelegateAndReturnOrderView() {
        OrderSubmitResult result =
                new OrderSubmitResult(999L, OrderStatus.FILLED, 1L, Instant.parse("2024-01-01T00:00:00Z"));
        when(tradingService.submit(any(OrderSubmitCommand.class))).thenReturn(result);

        OrderView view = tools.submitOrder(
                1L, "spot", "BTC/USDT", "buy", "market", new BigDecimal("0.1"), null, null, null, null);

        assertThat(view.orderId()).isEqualTo(999L);
        assertThat(view.status()).isEqualTo("FILLED");
        ArgumentCaptor<OrderSubmitCommand> captor = ArgumentCaptor.forClass(OrderSubmitCommand.class);
        verify(tradingService).submit(captor.capture());
        OrderSubmitCommand cmd = captor.getValue();
        assertThat(cmd.marketType()).isEqualTo(MarketType.SPOT);
        assertThat(cmd.side()).isEqualTo(OrderSide.BUY);
        assertThat(cmd.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(cmd.amount()).isEqualByComparingTo("0.1");
        assertThat(cmd.price()).isNull();
        assertThat(cmd.leverage()).isNull();
        assertThat(cmd.marginMode()).isNull();
        assertThat(cmd.positionEffect()).isNull();
    }

    @Test
    void submitOrder_perpOpenLong_shouldUsePerpFactoryWithContractFields() {
        OrderSubmitResult result =
                new OrderSubmitResult(888L, OrderStatus.NEW, 1L, Instant.parse("2024-01-01T00:00:00Z"));
        when(tradingService.submit(any(OrderSubmitCommand.class))).thenReturn(result);

        OrderView view = tools.submitOrder(
                1L,
                "perp",
                "BTC/USDT",
                "buy",
                "limit",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                10,
                "isolated",
                "open_long");

        assertThat(view.orderId()).isEqualTo(888L);
        ArgumentCaptor<OrderSubmitCommand> captor = ArgumentCaptor.forClass(OrderSubmitCommand.class);
        verify(tradingService).submit(captor.capture());
        OrderSubmitCommand cmd = captor.getValue();
        assertThat(cmd.marketType()).isEqualTo(MarketType.PERP);
        assertThat(cmd.leverage()).isEqualTo(10);
        assertThat(cmd.marginMode()).isEqualTo(MarginMode.ISOLATED);
        assertThat(cmd.positionEffect()).isEqualTo(PositionEffect.OPEN_LONG);
    }

    @Test
    void submitOrder_perpMissingContractFields_shouldThrow10002() {
        assertThatThrownBy(() -> tools.submitOrder(
                        1L,
                        "perp",
                        "BTC/USDT",
                        "buy",
                        "market",
                        new BigDecimal("0.1"),
                        null,
                        null,
                        "isolated",
                        "open_long"))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("PERP order requires");
    }

    @Test
    void submitOrder_riskRejected_shouldReturn200OrderViewRiskRejected() {
        when(tradingService.submit(any(OrderSubmitCommand.class)))
                .thenThrow(new RiskRejectedException(999L, "max notional exceeded"));

        OrderView view = tools.submitOrder(
                1L, "spot", "BTC/USDT", "buy", "market", new BigDecimal("1000"), null, null, null, null);

        assertThat(view.orderId()).isEqualTo(999L);
        assertThat(view.status()).isEqualTo("RISK_REJECTED");
        assertThat(view.reason()).contains("max notional");
    }

    @Test
    void submitOrder_invalidSide_shouldThrow10002() {
        assertThatThrownBy(() -> tools.submitOrder(
                        1L, "spot", "BTC/USDT", "hold", "market", new BigDecimal("0.1"), null, null, null, null))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("side");
    }

    // ── cancel_order ──

    @Test
    void cancelOrder_shouldDelegateAndReturnOrderView() {
        when(tradingService.cancel(15L)).thenReturn(new OrderCancelResult(15L, OrderStatus.PENDING_CANCEL, 2L));

        OrderView view = tools.cancelOrder(15L);

        assertThat(view.orderId()).isEqualTo(15L);
        assertThat(view.status()).isEqualTo("PENDING_CANCEL");
        verify(tradingService).cancel(15L);
    }

    // ── get_positions ──

    @Test
    void getPositions_validAccount_shouldReturnProjectedPositions() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        Position p = position(1L, 128L, "BTC/USDT", Position.SIDE_LONG, new BigDecimal("0.5"));
        when(positionService.findByAccount(1L)).thenReturn(List.of(p));
        when(positionEnricher.enrich(eq(p), eq(Exchange.BINANCE)))
                .thenReturn(
                        new PositionEnrichment(new BigDecimal("50000"), new BigDecimal("100"), new BigDecimal("2.5")));

        List<PositionView> views = tools.getPositions(1L);

        assertThat(views).hasSize(1);
        PositionView v = views.get(0);
        assertThat(v.positionId()).isEqualTo(128L);
        assertThat(v.symbol()).isEqualTo("BTC/USDT");
        assertThat(v.side()).isEqualTo(Position.SIDE_LONG);
        assertThat(v.currentPrice()).isEqualByComparingTo("50000");
        assertThat(v.unrealizedPnl()).isEqualByComparingTo("100");
        assertThat(v.cumulativeFunding()).isEqualByComparingTo("2.5");
        verify(accountService).getOwned(1L, 42L);
        verify(positionEnricher).enrich(p, Exchange.BINANCE);
    }

    @Test
    void getPositions_accountNotOwned_shouldThrowOwnershipViolation() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.getPositions(99L)).isInstanceOf(OwnershipViolationException.class);
    }

    // ── get_open_orders ──

    @Test
    void getOpenOrders_validAccount_shouldDelegateAndProject() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        Order o = new Order();
        o.setId(5L);
        o.setAccountId(1L);
        o.setSymbol("BTC/USDT");
        o.setStatus(OrderStatus.SUBMITTED);
        o.setOrderType(OrderType.LIMIT);
        o.setSide(OrderSide.BUY);
        o.setMarketType(MarketType.SPOT);
        o.setAmount(new BigDecimal("0.1"));
        o.setPrice(new BigDecimal("50000"));
        o.setFilledQty(new BigDecimal("0.05"));
        o.setFilledAvgPrice(new BigDecimal("50000"));
        o.setVersion(3L);
        when(tradingService.listOpenByAccount(1L)).thenReturn(List.of(o));

        List<OrderView> views = tools.getOpenOrders(1L);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).orderId()).isEqualTo(5L);
        assertThat(views.get(0).status()).isEqualTo("SUBMITTED");
        assertThat(views.get(0).filledQty()).isEqualByComparingTo("0.05");
        assertThat(views.get(0).marketType()).isEqualTo("SPOT");
        assertThat(views.get(0).side()).isEqualTo("BUY");
    }

    @Test
    void getOpenOrders_accountNotOwned_shouldThrowOwnershipViolation() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.getOpenOrders(99L)).isInstanceOf(OwnershipViolationException.class);
    }

    // ── close_position ──

    @Test
    void closePosition_shouldDelegateToTradingServiceClosePosition() {
        when(tradingService.closePosition(128L))
                .thenReturn(new OrderSubmitResult(100L, OrderStatus.FILLED, 1L, Instant.now()));

        OrderView view = tools.closePosition(128L);

        assertThat(view.orderId()).isEqualTo(100L);
        assertThat(view.status()).isEqualTo("FILLED");
        verify(tradingService).closePosition(128L);
    }

    @Test
    void closePosition_riskRejected_shouldReturn200RiskRejected() {
        when(tradingService.closePosition(128L)).thenThrow(new RiskRejectedException(102L, "daily loss limit"));

        OrderView view = tools.closePosition(128L);

        assertThat(view.orderId()).isEqualTo(102L);
        assertThat(view.status()).isEqualTo("RISK_REJECTED");
        assertThat(view.reason()).contains("daily loss");
    }

    @Test
    void closePosition_resourceNotFound_shouldBubbleUp() {
        when(tradingService.closePosition(999L)).thenThrow(new ResourceNotFoundException("position"));

        assertThatThrownBy(() -> tools.closePosition(999L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ── get_funding_history ──

    @Test
    void getFundingHistory_validAccount_shouldDelegateAndProject() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        FundingSettlement s = new FundingSettlement();
        s.setId(7L);
        s.setAccountId(1L);
        s.setSymbol("BTC/USDT");
        s.setFundingAmount(new BigDecimal("-0.5"));
        s.setQtyAtSettle(new BigDecimal("0.5"));
        s.setSettleTime(Instant.parse("2024-01-01T00:00:00Z"));
        when(fundingSettlementService.listByAccountAndSymbol(eq(1L), eq("BTC/USDT"), eq(50)))
                .thenReturn(List.of(s));

        List<FundingSettlementView> views = tools.getFundingHistory(1L, "BTC/USDT", null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(7L);
        assertThat(views.get(0).fundingAmount()).isEqualByComparingTo("-0.5");
        verify(accountService).getOwned(1L, 42L);
    }

    @Test
    void getFundingHistory_limitCappedAt200() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        when(fundingSettlementService.listByAccountAndSymbol(eq(1L), eq(null), eq(200)))
                .thenReturn(List.of());

        tools.getFundingHistory(1L, null, 500);

        verify(fundingSettlementService).listByAccountAndSymbol(1L, null, 200);
    }

    @Test
    void getFundingHistory_accountNotOwned_shouldThrowOwnershipViolation() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.getFundingHistory(99L, null, null))
                .isInstanceOf(OwnershipViolationException.class);
    }

    // ── get_liquidation_history ──

    @Test
    void getLiquidationHistory_validAccount_shouldDelegateAndProject() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        Fill f = new Fill();
        f.setId(11L);
        f.setOrderId(200L);
        f.setAccountId(1L);
        f.setSymbol("BTC/USDT");
        f.setSide(OrderSide.SELL);
        f.setPrice(new BigDecimal("40000"));
        f.setQty(new BigDecimal("0.5"));
        f.setFee(BigDecimal.ZERO);
        f.setExternalFillId("liq-128-1700000000000");
        f.setFilledAt(Instant.parse("2024-01-01T00:00:00Z"));
        f.setRealizedPnlDelta(new BigDecimal("-50"));
        when(liquidationService.listLiquidationsByAccount(eq(1L), eq("BTC/USDT"), eq(50)))
                .thenReturn(List.of(f));

        List<LiquidationView> views = tools.getLiquidationHistory(1L, "BTC/USDT", null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).fillId()).isEqualTo(11L);
        assertThat(views.get(0).price()).isEqualByComparingTo("40000");
        assertThat(views.get(0).realizedPnl()).isEqualByComparingTo("-50");
        assertThat(views.get(0).externalFillId()).startsWith("liq-");
        verify(accountService).getOwned(1L, 42L);
    }

    @Test
    void getLiquidationHistory_accountNotOwned_shouldThrowOwnershipViolation() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.getLiquidationHistory(99L, null, null))
                .isInstanceOf(OwnershipViolationException.class);
    }

    private static ExchangeAccount exchangeAccount(long id, long userId) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setUserId(userId);
        a.setExchange(Exchange.BINANCE);
        return a;
    }

    private static Position position(long accountId, long positionId, String symbol, String side, BigDecimal qty) {
        Position p = new Position();
        p.setId(positionId);
        p.setAccountId(accountId);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setQty(qty);
        return p;
    }
}
