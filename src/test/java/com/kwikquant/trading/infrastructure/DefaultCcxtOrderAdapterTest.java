package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.CcxtAuthExchangeFactory;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import io.github.ccxt.exchanges.pro.Okx;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultCcxtOrderAdapter} 单测。真实 CCXT Okx 调用路径已排除 JaCoCo(pom.xml:326);
 * 本测试通过 mock {@link CcxtAuthExchangeFactory}(返 mock {@link Okx})+ mock
 * {@link CcxtExchangeRegistry}(ccxtSymbol 直返)verify params 翻译正确性,不调真实 API。
 *
 * <p>覆盖:createOrder 四向翻译(OPEN_LONG/SHORT/CLOSE_LONG/SHORT)+ SPOT + Binance/Bitget 抛异常;
 * cancelOrder OKX happy path + 无 exchangeOrderId 抛异常; setLeverage/setMarginMode params 正确 +
 * Binance/Bitget 抛异常;createOrderWs/cancelOrderWs 调用参数含正确 symbol/posSide/reduceOnly/tdMode。
 *
 * <p>注:测试不验证 setPositionMode 幂等缓存细节(OKX 已设返 code=0 不动,真错 4b 验证)——
 * 预设 mockOkx.setPositionMode 返 CompletableFuture.completedFuture,避免影响 createOrder 主流程断言。
 */
class DefaultCcxtOrderAdapterTest {

    private static final String CCXT_PERP_SYMBOL = "BTC/USDT:USDT";
    private static final String EXCHANGE_ORDER_ID = "okx-order-123";

    private CcxtAuthExchangeFactory authFactory;
    private Okx mockOkx;
    private DefaultCcxtOrderAdapter adapter;

    @BeforeEach
    void setUp() {
        authFactory = mock(CcxtAuthExchangeFactory.class);
        mockOkx = mock(Okx.class);
        OkxOrderTranslator translator = new OkxOrderTranslator();
        adapter = new DefaultCcxtOrderAdapter(authFactory, translator);
        // OkxOrderTranslator 真实翻译 canonical→ccxtSymbol(BTC/USDT→BTC/USDT:USDT),无需 mock exchangeRegistry
        when(authFactory.createAuthExchange(any(ExchangeAccount.class), any(MarketType.class)))
                .thenReturn(mockOkx);
        // setPositionMode 默认返完成 future,不阻塞 createOrder 主流程
        when(mockOkx.setPositionMode(any(), any())).thenReturn(CompletableFuture.completedFuture(new Object()));
    }

    // ----- createOrder -----

    @Test
    void createOrder_okxPerpOpenLong_callsCreateOrderWsWithCorrectParams() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenReturn(ccxtOrderWithId(EXCHANGE_ORDER_ID));

        String exchangeOrderId = adapter.createOrder(acct, order);

        assertThat(exchangeOrderId).isEqualTo(EXCHANGE_ORDER_ID);
        // verify: symbol=BTC/USDT:USDT type=market side=buy amount=0.5 price=60000 params 含
        // posSide=long/reduceOnly=false/tdMode=isolated
        verify(mockOkx)
                .createOrderWs(
                        eq(CCXT_PERP_SYMBOL),
                        eq("market"),
                        eq("buy"),
                        eq(0.5d),
                        eq(60000d),
                        org.mockito.ArgumentMatchers.argThat(m -> "long".equals(m.get("posSide"))
                                && Boolean.FALSE.equals(m.get("reduceOnly"))
                                && "isolated".equals(m.get("tdMode"))));
    }

    @Test
    void createOrder_okxPerpOpenShort_translatesPosSideShortReduceOnlyFalse() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_SHORT, MarginMode.CROSS, OrderSide.SELL);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenReturn(ccxtOrderWithId(EXCHANGE_ORDER_ID));

        adapter.createOrder(acct, order);

        verify(mockOkx)
                .createOrderWs(
                        eq(CCXT_PERP_SYMBOL),
                        eq("market"),
                        eq("sell"), // OPEN_SHORT → side=sell
                        anyDouble(),
                        any(),
                        org.mockito.ArgumentMatchers.argThat(m -> "short".equals(m.get("posSide"))
                                && Boolean.FALSE.equals(m.get("reduceOnly"))
                                && "cross".equals(m.get("tdMode"))));
    }

    @Test
    void createOrder_okxPerpCloseLong_translatesPosSideLongReduceOnlyTrue() {
        ExchangeAccount acct = okxAccount();
        // CLOSE_LONG → side=SELL(平多卖出)
        Order order = perpOrder(PositionEffect.CLOSE_LONG, MarginMode.ISOLATED, OrderSide.SELL);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenReturn(ccxtOrderWithId(EXCHANGE_ORDER_ID));

        adapter.createOrder(acct, order);

        verify(mockOkx)
                .createOrderWs(
                        eq(CCXT_PERP_SYMBOL),
                        eq("market"),
                        eq("sell"),
                        anyDouble(),
                        any(),
                        org.mockito.ArgumentMatchers.argThat(m -> "long".equals(m.get("posSide"))
                                && Boolean.TRUE.equals(m.get("reduceOnly"))
                                && "isolated".equals(m.get("tdMode"))));
    }

    @Test
    void createOrder_okxPerpCloseShort_translatesPosSideShortReduceOnlyTrue() {
        ExchangeAccount acct = okxAccount();
        // CLOSE_SHORT → side=BUY(平空买入)
        Order order = perpOrder(PositionEffect.CLOSE_SHORT, MarginMode.CROSS, OrderSide.BUY);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenReturn(ccxtOrderWithId(EXCHANGE_ORDER_ID));

        adapter.createOrder(acct, order);

        verify(mockOkx)
                .createOrderWs(
                        eq(CCXT_PERP_SYMBOL),
                        eq("market"),
                        eq("buy"),
                        anyDouble(),
                        any(),
                        org.mockito.ArgumentMatchers.argThat(m -> "short".equals(m.get("posSide"))
                                && Boolean.TRUE.equals(m.get("reduceOnly"))
                                && "cross".equals(m.get("tdMode"))));
    }

    @Test
    void createOrder_okxSpot_positionEffectNull_omitsPosSideInParams() {
        ExchangeAccount acct = okxAccount();
        Order order = spotOrder(OrderType.LIMIT);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenReturn(ccxtOrderWithId(EXCHANGE_ORDER_ID));

        String exchangeOrderId = adapter.createOrder(acct, order);

        assertThat(exchangeOrderId).isEqualTo(EXCHANGE_ORDER_ID);
        // SPOT: ccxtSymbol 不带 :USDT 后缀(mock registry 直返 canonical)
        verify(mockOkx)
                .createOrderWs(
                        eq("BTC/USDT"),
                        eq("limit"),
                        eq("buy"),
                        anyDouble(),
                        any(),
                        org.mockito.ArgumentMatchers.argThat(m -> m.isEmpty()));
    }

    @Test
    void createOrder_okxLimitOrder_translatesTypeToLimit() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenReturn(ccxtOrderWithId(EXCHANGE_ORDER_ID));

        adapter.createOrder(acct, order);

        verify(mockOkx).createOrderWs(eq(CCXT_PERP_SYMBOL), eq("limit"), eq("buy"), anyDouble(), any(), anyMap());
    }

    @Test
    void createOrder_binance_throwsExchangeExceptionNotRetryable() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(2L);
        acct.setExchange(Exchange.BINANCE);
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);

        assertThatThrownBy(() -> adapter.createOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("暂只支持 OKX")
                .hasMessageContaining("BINANCE")
                .hasFieldOrPropertyWithValue("retryable", false);
        verify(mockOkx, never()).createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap());
    }

    @Test
    void createOrder_bitget_throwsExchangeException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(3L);
        acct.setExchange(Exchange.BITGET);
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);

        assertThatThrownBy(() -> adapter.createOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("BITGET");
        verify(mockOkx, never()).createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap());
    }

    @Test
    void createOrder_ccxtReturnsNullId_throwsRetryableExchangeException() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenReturn(ccxtOrderWithId(null)); // null id

        assertThatThrownBy(() -> adapter.createOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("null/blank id")
                .hasFieldOrPropertyWithValue("retryable", true);
    }

    @Test
    void createOrder_createOrderWsThrows_wrapsAsRetryableExchangeException() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        when(mockOkx.createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap()))
                .thenThrow(new RuntimeException("network timeout"));

        assertThatThrownBy(() -> adapter.createOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("network timeout")
                .hasFieldOrPropertyWithValue("retryable", true)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void createOrder_conditionalOrderType_throwsNonRetryable() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        order.setOrderType(OrderType.STOP_MARKET); // 条件单

        assertThatThrownBy(() -> adapter.createOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("条件单")
                .hasFieldOrPropertyWithValue("retryable", false);
        verify(mockOkx, never()).createOrderWs(anyString(), anyString(), anyString(), anyDouble(), any(), anyMap());
    }

    // ----- cancelOrder -----

    @Test
    void cancelOrder_okx_callsCancelOrderWsWithIdAndSymbol() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        order.setExchangeOrderId(EXCHANGE_ORDER_ID);
        when(mockOkx.cancelOrderWs(anyString(), anyString(), anyMap())).thenReturn(ccxtOrderWithId(EXCHANGE_ORDER_ID));

        adapter.cancelOrder(acct, order);

        verify(mockOkx).cancelOrderWs(eq(EXCHANGE_ORDER_ID), eq(CCXT_PERP_SYMBOL), anyMap());
    }

    @Test
    void cancelOrder_noExchangeOrderId_throwsNonRetryable() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        // exchangeOrderId 未设
        assertThatThrownBy(() -> adapter.cancelOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("no exchangeOrderId")
                .hasFieldOrPropertyWithValue("retryable", false);
        verify(mockOkx, never()).cancelOrderWs(anyString(), anyString(), anyMap());
    }

    @Test
    void cancelOrder_binance_throwsExchangeException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(2L);
        acct.setExchange(Exchange.BINANCE);
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        order.setExchangeOrderId(EXCHANGE_ORDER_ID);

        assertThatThrownBy(() -> adapter.cancelOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("BINANCE");
    }

    @Test
    void cancelOrder_cancelOrderWsThrows_wrapsAsRetryable() {
        ExchangeAccount acct = okxAccount();
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED, OrderSide.BUY);
        order.setExchangeOrderId(EXCHANGE_ORDER_ID);
        when(mockOkx.cancelOrderWs(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("order not found"));

        assertThatThrownBy(() -> adapter.cancelOrder(acct, order))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("order not found")
                .hasFieldOrPropertyWithValue("retryable", true)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ----- setLeverage -----

    @Test
    void setLeverage_okx_callsExchangeSetLeverageWithMgnModeAndPosSide() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setLeverage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new Object()));

        adapter.setLeverage(acct, CCXT_PERP_SYMBOL, 10, MarginMode.ISOLATED, PositionSide.LONG);

        verify(mockOkx)
                .setLeverage(
                        eq(10),
                        eq(CCXT_PERP_SYMBOL),
                        org.mockito.ArgumentMatchers.argThat((java.util.Map<String, Object> m) ->
                                "isolated".equals(m.get("mgnMode")) && "long".equals(m.get("posSide"))));
    }

    @Test
    void setLeverage_okxCrossShort_translatesMgnModeCrossPosSideShort() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setLeverage(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new Object()));

        adapter.setLeverage(acct, CCXT_PERP_SYMBOL, 20, MarginMode.CROSS, PositionSide.SHORT);

        verify(mockOkx)
                .setLeverage(
                        eq(20),
                        eq(CCXT_PERP_SYMBOL),
                        org.mockito.ArgumentMatchers.argThat((java.util.Map<String, Object> m) ->
                                "cross".equals(m.get("mgnMode")) && "short".equals(m.get("posSide"))));
    }

    @Test
    void setLeverage_binance_throwsExchangeException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(2L);
        acct.setExchange(Exchange.BINANCE);
        assertThatThrownBy(
                        () -> adapter.setLeverage(acct, CCXT_PERP_SYMBOL, 10, MarginMode.ISOLATED, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("BINANCE");
        verify(mockOkx, never()).setLeverage(any(), any(), any());
    }

    @Test
    void setLeverage_ccxtThrows_wrapsAsRetryable() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setLeverage(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("leverage too high")));

        assertThatThrownBy(
                        () -> adapter.setLeverage(acct, CCXT_PERP_SYMBOL, 200, MarginMode.ISOLATED, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("leverage too high")
                .hasFieldOrPropertyWithValue("retryable", true);
    }

    // ----- setMarginMode -----

    @Test
    void setMarginMode_okx_callsExchangeSetMarginModeWithLever() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setMarginMode(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new Object()));

        adapter.setMarginMode(acct, CCXT_PERP_SYMBOL, MarginMode.ISOLATED, 10, PositionSide.LONG);

        verify(mockOkx)
                .setMarginMode(
                        eq("isolated"),
                        eq(CCXT_PERP_SYMBOL),
                        org.mockito.ArgumentMatchers.argThat((java.util.Map<String, Object> m) ->
                                Integer.valueOf(10).equals(m.get("lever")) && "long".equals(m.get("posSide"))));
    }

    @Test
    void setMarginMode_okxCross_translatesTdModeCross() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setMarginMode(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new Object()));

        adapter.setMarginMode(acct, CCXT_PERP_SYMBOL, MarginMode.CROSS, 20, PositionSide.SHORT);

        verify(mockOkx).setMarginMode(eq("cross"), eq(CCXT_PERP_SYMBOL), any());
    }

    @Test
    void setMarginMode_binance_throwsExchangeException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(2L);
        acct.setExchange(Exchange.BINANCE);
        assertThatThrownBy(
                        () -> adapter.setMarginMode(acct, CCXT_PERP_SYMBOL, MarginMode.ISOLATED, 10, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("BINANCE");
        verify(mockOkx, never()).setMarginMode(any(), any(), any());
    }

    @Test
    void setMarginMode_ccxtThrows_wrapsAsRetryable() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setMarginMode(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("lever should be 1-125")));

        assertThatThrownBy(
                        () -> adapter.setMarginMode(acct, CCXT_PERP_SYMBOL, MarginMode.ISOLATED, 0, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("lever should be 1-125")
                .hasFieldOrPropertyWithValue("retryable", true);
    }

    // ----- helpers -----

    private static ExchangeAccount okxAccount() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        acct.setExchange(Exchange.OKX);
        return acct;
    }

    private static Order perpOrder(PositionEffect effect, MarginMode mode, OrderSide side) {
        Order order = new Order();
        order.setId(1L);
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.PERP);
        order.setSide(side);
        order.setOrderType(OrderType.MARKET);
        order.setAmount(new BigDecimal("0.5"));
        order.setPrice(new BigDecimal("60000"));
        order.setPositionEffect(effect);
        order.setMarginMode(mode);
        order.setLeverage(10);
        return order;
    }

    private static Order spotOrder(OrderType type) {
        Order order = new Order();
        order.setId(2L);
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.SPOT);
        order.setSide(OrderSide.BUY);
        order.setOrderType(type);
        order.setAmount(new BigDecimal("0.1"));
        order.setPrice(new BigDecimal("60000"));
        return order;
    }

    /** 构造真实 {@link io.github.ccxt.types.Order} 实例(该类 final 不能 mock),设 id 字段。 */
    private static io.github.ccxt.types.Order ccxtOrderWithId(String id) {
        io.github.ccxt.types.Order o = new io.github.ccxt.types.Order((Object) null);
        o.id = id;
        return o;
    }
}
