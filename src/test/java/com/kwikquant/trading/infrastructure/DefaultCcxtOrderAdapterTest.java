package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private static final String CANONICAL_PERP_SYMBOL = "BTC/USDT";
    private static final String EXCHANGE_ORDER_ID = "okx-order-123";

    private CcxtAuthExchangeFactory authFactory;
    private Okx mockOkx;
    private OkxRestClient okxRestClient;
    private OrderMapper orderMapper;
    private DefaultCcxtOrderAdapter adapter;

    @BeforeEach
    void setUp() {
        authFactory = mock(CcxtAuthExchangeFactory.class);
        mockOkx = mock(Okx.class);
        OkxOrderTranslator translator = new OkxOrderTranslator();
        okxRestClient = mock(OkxRestClient.class);
        orderMapper = mock(OrderMapper.class);
        adapter = new DefaultCcxtOrderAdapter(authFactory, translator, okxRestClient, orderMapper);
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
                                && "isolated".equals(m.get("tdMode"))
                                && "KQ1".equals(m.get("clOrdId"))));
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
                        org.mockito.ArgumentMatchers.argThat(
                                m -> java.util.Map.of("clOrdId", "KQ2").equals(m)));
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

        adapter.setLeverage(acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, 10, MarginMode.ISOLATED, PositionSide.LONG);

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

        adapter.setLeverage(acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, 20, MarginMode.CROSS, PositionSide.SHORT);

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
        assertThatThrownBy(() -> adapter.setLeverage(
                        acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, 10, MarginMode.ISOLATED, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("BINANCE");
        verify(mockOkx, never()).setLeverage(any(), any(), any());
    }

    @Test
    void setLeverage_ccxtThrows_wrapsAsRetryable() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setLeverage(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("leverage too high")));

        assertThatThrownBy(() -> adapter.setLeverage(
                        acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, 200, MarginMode.ISOLATED, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("leverage too high")
                .hasFieldOrPropertyWithValue("retryable", true);
    }

    // ----- setMarginMode -----

    @Test
    void setMarginMode_okx_callsExchangeSetMarginModeWithLever() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setMarginMode(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(new Object()));

        adapter.setMarginMode(acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, MarginMode.ISOLATED, 10, PositionSide.LONG);

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

        adapter.setMarginMode(acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, MarginMode.CROSS, 20, PositionSide.SHORT);

        verify(mockOkx).setMarginMode(eq("cross"), eq(CCXT_PERP_SYMBOL), any());
    }

    @Test
    void setMarginMode_binance_throwsExchangeException() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(2L);
        acct.setExchange(Exchange.BINANCE);
        assertThatThrownBy(() -> adapter.setMarginMode(
                        acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, MarginMode.ISOLATED, 10, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("BINANCE");
        verify(mockOkx, never()).setMarginMode(any(), any(), any());
    }

    @Test
    void setMarginMode_ccxtThrows_wrapsAsRetryable() {
        ExchangeAccount acct = okxAccount();
        when(mockOkx.setMarginMode(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("lever should be 1-125")));

        assertThatThrownBy(() -> adapter.setMarginMode(
                        acct, CANONICAL_PERP_SYMBOL, MarketType.PERP, MarginMode.ISOLATED, 0, PositionSide.LONG))
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

    // ----- 4b pollFills(轮询 REST 替代 WS) -----

    @Test
    void fetchSnapshot_positionFailureIsExplicit() {
        ExchangeAccount acct = okxAccount();
        when(okxRestClient.fetchPositions(acct)).thenThrow(new ExchangeException("HTTP 401", true));

        assertThatThrownBy(() -> adapter.fetchSnapshot(acct))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("fetchPositions")
                .hasCauseInstanceOf(ExchangeException.class);
    }

    /** 构造 OKX /api/v5/fills raw Map(ordId/tradeId/px/qty/fee/feeCcy/execType/ts)。 */
    private static java.util.Map<String, Object> fillRaw(String ordId, String tradeId) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ordId", ordId);
        m.put("tradeId", tradeId);
        m.put("fillPx", "60000");
        m.put("fillSz", "0.01");
        m.put("fee", "-0.01");
        m.put("feeCcy", "USDT");
        m.put("execType", "T");
        m.put("ts", "1719000000000");
        return m;
    }

    @Test
    void pollFills_firstPollProcessesAllFillsOldestFirst() {
        ExchangeAccount acct = okxAccount();
        // OKX 返 desc(最新在前):tradeId 200, 100
        when(okxRestClient.fetchFills(acct))
                .thenReturn(java.util.List.of(fillRaw("ord-2", "200"), fillRaw("ord-1", "100")));
        @SuppressWarnings("unchecked")
        CcxtOrderAdapter.EventHandler<CcxtOrderAdapter.FillEvent> handler = mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenReturn(true);
        Order first = new Order();
        first.setId(11L);
        Order second = new Order();
        second.setId(22L);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-1")).thenReturn(first);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-2")).thenReturn(second);

        adapter.pollFills(acct, handler);

        org.mockito.ArgumentCaptor<CcxtOrderAdapter.FillEvent> captor =
                org.mockito.ArgumentCaptor.forClass(CcxtOrderAdapter.FillEvent.class);
        verify(handler, times(2)).handle(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CcxtOrderAdapter.FillEvent::externalFillId)
                .containsExactly("100", "200");
    }

    @Test
    void pollFills_secondPoll_pushesNewFillsWithLocalOrderId() {
        ExchangeAccount acct = okxAccount();
        when(okxRestClient.fetchFills(acct))
                .thenReturn(java.util.List.of(fillRaw("ord-2", "200"), fillRaw("ord-1", "100")));
        @SuppressWarnings("unchecked")
        CcxtOrderAdapter.EventHandler<CcxtOrderAdapter.FillEvent> handler = mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenReturn(true);
        Order old1 = new Order();
        old1.setId(10L);
        Order old2 = new Order();
        old2.setId(20L);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-1")).thenReturn(old1);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-2")).thenReturn(old2);
        adapter.pollFills(acct, handler);

        // 第二次:新 fill tradeId=300(>200),旧 200 已推过
        when(okxRestClient.fetchFills(acct))
                .thenReturn(java.util.List.of(fillRaw("ord-3", "300"), fillRaw("ord-2", "200")));
        Order local = new Order();
        local.setId(77L);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-3")).thenReturn(local);

        adapter.pollFills(acct, handler);

        // 只推 tradeId=300(ord-3),填 orderId=77(查 OrderMapper)
        org.mockito.ArgumentCaptor<CcxtOrderAdapter.FillEvent> captor =
                org.mockito.ArgumentCaptor.forClass(CcxtOrderAdapter.FillEvent.class);
        verify(handler, times(3)).handle(captor.capture());
        CcxtOrderAdapter.FillEvent newest = captor.getAllValues().get(2);
        assertThat(newest.orderId()).isEqualTo(77L);
        assertThat(newest.exchangeOrderId()).isEqualTo("ord-3");
        assertThat(newest.externalFillId()).isEqualTo("300");

        adapter.pollFills(acct, handler);
        verify(handler, times(3)).handle(any());
    }

    @Test
    void pollFills_handlerFailureDoesNotAdvanceCursorAndRetries() {
        ExchangeAccount acct = okxAccount();
        when(okxRestClient.fetchFills(acct))
                .thenReturn(java.util.List.of(fillRaw("ord-2", "200"), fillRaw("ord-1", "100")));
        Order o1 = new Order();
        o1.setId(11L);
        Order o2 = new Order();
        o2.setId(22L);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-1")).thenReturn(o1);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-2")).thenReturn(o2);
        CcxtOrderAdapter.EventHandler<CcxtOrderAdapter.FillEvent> handler = mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenThrow(new RuntimeException("db down")).thenReturn(true);

        adapter.pollFills(acct, handler);
        adapter.pollFills(acct, handler);

        org.mockito.ArgumentCaptor<CcxtOrderAdapter.FillEvent> captor =
                org.mockito.ArgumentCaptor.forClass(CcxtOrderAdapter.FillEvent.class);
        verify(handler, times(3)).handle(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CcxtOrderAdapter.FillEvent::externalFillId)
                .containsExactly("100", "100", "200");
    }

    @Test
    void pollFills_fillOfUnmanagedOrder_skipsAndAdvancesCursor() {
        // 毒事件防护:交易所手工单(clOrdId 无 KQ 前缀,本地无订单)的成交若阻塞游标,
        // 会永久卡死该账户全部成交流水线 → 记审计日志后跳过并推进游标,后续成交正常处理。
        ExchangeAccount acct = okxAccount();
        java.util.Map<String, Object> manual = fillRaw("ord-manual", "100");
        manual.put("clOrdId", "userManual123");
        java.util.Map<String, Object> managed = fillRaw("ord-1", "200");
        managed.put("clOrdId", "KQ25");
        when(okxRestClient.fetchFills(acct)).thenReturn(java.util.List.of(managed, manual));
        Order local = new Order();
        local.setId(77L);
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-1")).thenReturn(local);
        @SuppressWarnings("unchecked")
        CcxtOrderAdapter.EventHandler<CcxtOrderAdapter.FillEvent> handler = mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenReturn(true);

        adapter.pollFills(acct, handler);

        // 手工单成交(tradeId=100)被跳过,本系统成交(tradeId=200)正常推送
        org.mockito.ArgumentCaptor<CcxtOrderAdapter.FillEvent> captor =
                org.mockito.ArgumentCaptor.forClass(CcxtOrderAdapter.FillEvent.class);
        verify(handler, times(1)).handle(captor.capture());
        assertThat(captor.getValue().externalFillId()).isEqualTo("200");

        // 游标已推进过 100:再次轮询同一批不重放
        adapter.pollFills(acct, handler);
        verify(handler, times(1)).handle(any());
    }

    @Test
    void pollFills_pendingNewOrderWithoutExchangeOrderId_resolvesByClientOrderId() {
        // PENDING_NEW 订单 exchangeOrderId 尚未落库:按 KQ clOrdId 反解归属,成交照常处理。
        ExchangeAccount acct = okxAccount();
        java.util.Map<String, Object> raw = fillRaw("ord-ex", "100");
        raw.put("clOrdId", "KQ25"); // base36 反解 → 订单 ID 77
        when(okxRestClient.fetchFills(acct)).thenReturn(java.util.List.of(raw));
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-ex")).thenReturn(null);
        Order pending = new Order();
        pending.setId(77L);
        pending.setAccountId(acct.getId());
        when(orderMapper.findById(77L)).thenReturn(pending);
        @SuppressWarnings("unchecked")
        CcxtOrderAdapter.EventHandler<CcxtOrderAdapter.FillEvent> handler = mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenReturn(true);

        adapter.pollFills(acct, handler);

        org.mockito.ArgumentCaptor<CcxtOrderAdapter.FillEvent> captor =
                org.mockito.ArgumentCaptor.forClass(CcxtOrderAdapter.FillEvent.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(77L);
    }

    @Test
    void pollFills_clientOrderIdOfOtherAccount_skipsAsUnmanaged() {
        // KQ clOrdId 反解出的订单属于其他账户 → 不可归属,跳过防误归属。
        ExchangeAccount acct = okxAccount();
        java.util.Map<String, Object> raw = fillRaw("ord-ex", "100");
        raw.put("clOrdId", "KQ25");
        when(okxRestClient.fetchFills(acct)).thenReturn(java.util.List.of(raw));
        when(orderMapper.findByExchangeOrderId(acct.getId(), "ord-ex")).thenReturn(null);
        Order other = new Order();
        other.setId(77L);
        other.setAccountId(acct.getId() + 999L); // 其他账户
        when(orderMapper.findById(77L)).thenReturn(other);
        @SuppressWarnings("unchecked")
        CcxtOrderAdapter.EventHandler<CcxtOrderAdapter.FillEvent> handler = mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenReturn(true);

        adapter.pollFills(acct, handler);

        verify(handler, never()).handle(any());
    }

    @Test
    void pollFills_fetchFillsFails_logsWarnDoesNotThrow() {
        ExchangeAccount acct = okxAccount();
        when(okxRestClient.fetchFills(acct)).thenThrow(new ExchangeException("network", true));
        @SuppressWarnings("unchecked")
        CcxtOrderAdapter.EventHandler<CcxtOrderAdapter.FillEvent> handler = mock(CcxtOrderAdapter.EventHandler.class);

        // 轮询容错:fetchFills 失败不抛(log warn),下次重试
        adapter.pollFills(acct, handler);
        verify(handler, never()).handle(any());
    }

    @Test
    void pollBills_firstPollProcessesAndRepeatedPollDoesNotRepeat() {
        ExchangeAccount acct = okxAccount();
        java.util.Map<String, Object> bill = new java.util.LinkedHashMap<>();
        bill.put("billId", "500");
        bill.put("type", "8");
        bill.put("instId", "BTC-USDT-SWAP");
        bill.put("posSide", "long");
        bill.put("pnl", "-0.01");
        bill.put("ts", "1719000000000");
        when(okxRestClient.fetchBills(acct, null)).thenReturn(java.util.List.of(bill));
        CcxtOrderAdapter.EventHandler<com.kwikquant.trading.domain.BillRecord> handler =
                mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenReturn(true);

        adapter.pollBills(acct, handler);
        adapter.pollBills(acct, handler);

        verify(handler, times(1)).handle(argThat(event -> "500".equals(event.billId())));
    }

    @Test
    void pollBills_handlerFailureDoesNotAdvanceCursorAndRetries() {
        ExchangeAccount acct = okxAccount();
        java.util.Map<String, Object> bill = new java.util.LinkedHashMap<>();
        bill.put("billId", "500");
        bill.put("type", "8");
        bill.put("instId", "BTC-USDT-SWAP");
        when(okxRestClient.fetchBills(acct, null)).thenReturn(java.util.List.of(bill));
        CcxtOrderAdapter.EventHandler<com.kwikquant.trading.domain.BillRecord> handler =
                mock(CcxtOrderAdapter.EventHandler.class);
        when(handler.handle(any())).thenThrow(new RuntimeException("db down")).thenReturn(true);

        adapter.pollBills(acct, handler);
        adapter.pollBills(acct, handler);

        verify(handler, times(2)).handle(argThat(event -> "500".equals(event.billId())));
    }

    @Test
    void setPositionMode_okxAccount_delegatesToOkxRestClient() {
        ExchangeAccount okx = new ExchangeAccount();
        okx.setExchange(Exchange.OKX);
        okx.setId(1L);
        adapter.setPositionMode(okx);
        verify(okxRestClient).setPositionMode(okx);
    }

    @Test
    void setPositionMode_nonOkx_throws() {
        ExchangeAccount binance = new ExchangeAccount();
        binance.setExchange(Exchange.BINANCE);
        binance.setId(2L);
        assertThatThrownBy(() -> adapter.setPositionMode(binance))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("待补齐");
        verify(okxRestClient, never()).setPositionMode(any());
    }
}
