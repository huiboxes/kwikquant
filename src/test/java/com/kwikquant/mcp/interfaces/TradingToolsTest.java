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
import com.kwikquant.mcp.interfaces.view.OrderView;
import com.kwikquant.mcp.interfaces.view.PositionView;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.OrderCancelResult;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.application.TradingService;
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
 * {@link TradingTools} 单测。验证：5 个 @McpTool 的 service 委托、OrderView/PositionView 投影、
 * submitOrder/closePosition 风控拒绝 catch 转 OrderView{RISK_REJECTED} 返 200、closePosition 双向反向市价单、
 * getPositions/getOpenOrders/closePosition 前置 getOwned 校验（不属用户抛 1002）、flat/空持仓抛 4001。
 */
class TradingToolsTest {

    private TradingService tradingService;
    private PositionService positionService;
    private ExchangeAccountService accountService;
    private TradingTools tools;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        positionService = mock(PositionService.class);
        accountService = mock(ExchangeAccountService.class);
        tools = new TradingTools(tradingService, positionService, accountService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── submit_order ──

    @Test
    void submitOrder_marketBuy_shouldDelegateAndReturnOrderView() {
        OrderSubmitResult result =
                new OrderSubmitResult(999L, OrderStatus.FILLED, 1L, Instant.parse("2024-01-01T00:00:00Z"));
        when(tradingService.submit(any(OrderSubmitCommand.class))).thenReturn(result);

        OrderView view = tools.submitOrder(1L, "spot", "BTC/USDT", "buy", "market", new BigDecimal("0.1"), null);

        assertThat(view.orderId()).isEqualTo(999L);
        assertThat(view.status()).isEqualTo("FILLED");
        ArgumentCaptor<OrderSubmitCommand> captor = ArgumentCaptor.forClass(OrderSubmitCommand.class);
        verify(tradingService).submit(captor.capture());
        OrderSubmitCommand cmd = captor.getValue();
        assertThat(cmd.accountId()).isEqualTo(1L);
        assertThat(cmd.marketType()).isEqualTo(MarketType.SPOT);
        assertThat(cmd.side()).isEqualTo(OrderSide.BUY);
        assertThat(cmd.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(cmd.amount()).isEqualByComparingTo("0.1");
        assertThat(cmd.price()).isNull();
    }

    @Test
    void submitOrder_riskRejected_shouldReturn200OrderViewRiskRejected() {
        when(tradingService.submit(any(OrderSubmitCommand.class)))
                .thenThrow(new RiskRejectedException(999L, "max notional exceeded"));

        OrderView view = tools.submitOrder(1L, "spot", "BTC/USDT", "buy", "market", new BigDecimal("1000"), null);

        assertThat(view.orderId()).isEqualTo(999L);
        assertThat(view.status()).isEqualTo("RISK_REJECTED");
        assertThat(view.reason()).contains("max notional");
    }

    @Test
    void submitOrder_invalidSide_shouldThrow10002() {
        assertThatThrownBy(
                        () -> tools.submitOrder(1L, "spot", "BTC/USDT", "hold", "market", new BigDecimal("0.1"), null))
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
        Position p = position(1L, "BTC/USDT", Position.SIDE_LONG, new BigDecimal("0.5"));
        when(positionService.findByAccount(1L)).thenReturn(List.of(p));

        List<PositionView> views = tools.getPositions(1L);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).symbol()).isEqualTo("BTC/USDT");
        assertThat(views.get(0).side()).isEqualTo(Position.SIDE_LONG);
        verify(accountService).getOwned(1L, 42L);
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
    }

    @Test
    void getOpenOrders_accountNotOwned_shouldThrowOwnershipViolation() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.getOpenOrders(99L)).isInstanceOf(OwnershipViolationException.class);
    }

    // ── close_position ──

    @Test
    void closePosition_longPosition_shouldSubmitSellMarketOrder() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        Position p = position(1L, "BTC/USDT", Position.SIDE_LONG, new BigDecimal("0.5"));
        when(positionService.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(p);
        when(tradingService.submit(any(OrderSubmitCommand.class)))
                .thenReturn(new OrderSubmitResult(100L, OrderStatus.FILLED, 1L, Instant.now()));

        OrderView view = tools.closePosition(1L, "spot", "BTC/USDT");

        assertThat(view.status()).isEqualTo("FILLED");
        ArgumentCaptor<OrderSubmitCommand> captor = ArgumentCaptor.forClass(OrderSubmitCommand.class);
        verify(tradingService).submit(captor.capture());
        assertThat(captor.getValue().side()).isEqualTo(OrderSide.SELL);
        assertThat(captor.getValue().orderType()).isEqualTo(OrderType.MARKET);
        assertThat(captor.getValue().amount()).isEqualByComparingTo("0.5");
    }

    @Test
    void closePosition_shortPosition_shouldSubmitBuyMarketOrder() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        Position p = position(1L, "ETH/USDT", Position.SIDE_SHORT, new BigDecimal("2.0"));
        when(positionService.findByAccountAndSymbol(1L, "ETH/USDT")).thenReturn(p);
        when(tradingService.submit(any(OrderSubmitCommand.class)))
                .thenReturn(new OrderSubmitResult(101L, OrderStatus.FILLED, 1L, Instant.now()));

        OrderView view = tools.closePosition(1L, "perp", "ETH/USDT");

        assertThat(view.status()).isEqualTo("FILLED");
        ArgumentCaptor<OrderSubmitCommand> captor = ArgumentCaptor.forClass(OrderSubmitCommand.class);
        verify(tradingService).submit(captor.capture());
        assertThat(captor.getValue().side()).isEqualTo(OrderSide.BUY);
        assertThat(captor.getValue().amount()).isEqualByComparingTo("2.0");
    }

    @Test
    void closePosition_flatPosition_shouldThrow404() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        Position flat = position(1L, "BTC/USDT", Position.SIDE_FLAT, BigDecimal.ZERO);
        when(positionService.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(flat);

        assertThatThrownBy(() -> tools.closePosition(1L, "spot", "BTC/USDT"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void closePosition_positionNotFound_shouldThrow404() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        when(positionService.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(null);

        assertThatThrownBy(() -> tools.closePosition(1L, "spot", "BTC/USDT"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void closePosition_accountNotOwned_shouldThrowOwnershipViolation() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.closePosition(99L, "spot", "BTC/USDT"))
                .isInstanceOf(OwnershipViolationException.class);
    }

    @Test
    void closePosition_riskRejected_shouldReturn200RiskRejected() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        Position p = position(1L, "BTC/USDT", Position.SIDE_LONG, new BigDecimal("0.5"));
        when(positionService.findByAccountAndSymbol(1L, "BTC/USDT")).thenReturn(p);
        when(tradingService.submit(any(OrderSubmitCommand.class)))
                .thenThrow(new RiskRejectedException(102L, "daily loss limit"));

        OrderView view = tools.closePosition(1L, "spot", "BTC/USDT");

        assertThat(view.status()).isEqualTo("RISK_REJECTED");
        assertThat(view.reason()).contains("daily loss");
    }

    private static ExchangeAccount exchangeAccount(long id, long userId) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setUserId(userId);
        a.setExchange(Exchange.BINANCE);
        return a;
    }

    private static Position position(long accountId, String symbol, String side, BigDecimal qty) {
        Position p = new Position();
        p.setAccountId(accountId);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setQty(qty);
        return p;
    }
}
