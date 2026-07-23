package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.InsufficientMarginException;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * H3 回归测试：BUY 单部分成交后撤单/GTD 过期，unfreezeBalance 必须按剩余未成交比例折算，
 * 而不是用下单时冻结的原始全额（否则已成交部分对应的冻结额会被重复释放，虚增可用余额）。
 *
 * <p>阶段2d 新增 PERP 分支测试(§3.1 / §13 拍板 1):OPEN_* 冻 initialMargin / CLOSE_* noop /
 * 余额不足抛 InsufficientMarginException / unfreeze 按比例折算。
 */
class TradingTransactionHelperTest {

    private OrderMapper orderMapper;
    private BalanceService balanceService;
    private MarketDataService marketDataService;
    private TradingTransactionHelper txHelper;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        balanceService = mock(BalanceService.class);
        marketDataService = mock(MarketDataService.class);
        txHelper = new TradingTransactionHelper(orderMapper, balanceService, marketDataService);
    }

    private static ExchangeAccount paperAccount() {
        ExchangeAccount account = new ExchangeAccount();
        account.setId(7L);
        account.setPaperTrading(true);
        return account;
    }

    @Test
    void unfreezeBalance_buyOrderPartiallyFilled_releasesOnlyRemainingProportion() {
        // 冻结 1000 USDT，成交 30%（3/10），剩余 70% 应解冻 = 1000 * 7/10 = 700
        Order order = new Order();
        order.setId(1L);
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.SPOT);
        order.setSide(OrderSide.BUY);
        order.setAmount(new BigDecimal("10"));
        order.setFilledQty(new BigDecimal("3"));
        order.setFrozenQuoteAmount(new BigDecimal("1000"));

        txHelper.unfreezeBalance(order, paperAccount());

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).unfreeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("700"));
    }

    @Test
    void unfreezeBalance_buyOrderNeverFilled_releasesFullAmount() {
        Order order = new Order();
        order.setId(2L);
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.SPOT);
        order.setSide(OrderSide.BUY);
        order.setAmount(new BigDecimal("10"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setFrozenQuoteAmount(new BigDecimal("1000"));

        txHelper.unfreezeBalance(order, paperAccount());

        verify(balanceService).unfreeze(eq(7L), eq(true), eq("USDT"), eq(new BigDecimal("1000")));
    }

    @Test
    void unfreezeBalance_sellOrder_releasesRemainingBaseQty() {
        Order order = new Order();
        order.setId(3L);
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.SPOT);
        order.setSide(OrderSide.SELL);
        order.setAmount(new BigDecimal("10"));
        order.setFilledQty(new BigDecimal("4"));

        txHelper.unfreezeBalance(order, paperAccount());

        verify(balanceService).unfreeze(eq(7L), eq(true), eq("BTC"), eq(new BigDecimal("6")));
    }

    @Test
    void unfreezeBalance_liveAccount_isNoop() {
        Order order = new Order();
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.SPOT);
        order.setSide(OrderSide.BUY);
        order.setAmount(new BigDecimal("10"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setFrozenQuoteAmount(new BigDecimal("1000"));
        ExchangeAccount liveAccount = new ExchangeAccount();
        liveAccount.setId(9L);
        liveAccount.setPaperTrading(false);

        txHelper.unfreezeBalance(order, liveAccount);

        verify(balanceService, never()).unfreeze(anyLong(), anyBoolean(), anyString(), any());
    }

    // ---------- 阶段2d:PERP freezeBalance / unfreezeBalance 分支 ----------

    /** PERP OPEN_LONG 冻 initialMargin = qty*price/leverage,写 frozenQuoteAmount 供 unfreeze 折算。 */
    @Test
    void freezeBalance_perpOpenLong_freezesInitialMarginQuote() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("0.1"));

        txHelper.freezeBalance(order, paperAccount(), null);

        // 0.1 × 42000 / 10 = 420.0 USDT
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).freeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("420"));
        assertThat(order.getFrozenQuoteAmount()).isEqualByComparingTo(new BigDecimal("420"));
        verify(orderMapper)
                .updateFrozenQuoteAmount(eq(1L), argThat(bd -> bd != null && bd.compareTo(new BigDecimal("420")) == 0));
    }

    /** PERP OPEN_SHORT 冻同 initialMargin(方向无关,只看 notional/leverage)。 */
    @Test
    void freezeBalance_perpOpenShort_freezesInitialMarginQuote() {
        Order order = newPerpOrder(PositionEffect.OPEN_SHORT, OrderSide.SELL, new BigDecimal("0.1"));

        txHelper.freezeBalance(order, paperAccount(), null);

        verify(balanceService)
                .freeze(
                        eq(7L),
                        eq(true),
                        eq("USDT"),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("420")) == 0));
        assertThat(order.getFrozenQuoteAmount()).isEqualByComparingTo(new BigDecimal("420"));
    }

    /** PERP OPEN_* MARKET 单(无 price)用 marketPrice 估算 initialMargin。 */
    @Test
    void freezeBalance_perpOpenMarketOrder_usesMarketPrice() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("0.1"));
        order.setPrice(null); // MARKET 单
        order.setOrderType(com.kwikquant.shared.types.OrderType.MARKET);

        txHelper.freezeBalance(order, paperAccount(), new BigDecimal("42500"));

        // 0.1 × 42500 / 10 = 425 USDT
        verify(balanceService)
                .freeze(
                        eq(7L),
                        eq(true),
                        eq("USDT"),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("425")) == 0));
    }

    /** PERP OPEN_* 余额不足 → 抛 InsufficientMarginException(extends InsufficientBalanceException)。 */
    @Test
    void freezeBalance_perpOpenInsufficientBalance_throwsInsufficientMargin() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("0.1"));
        doThrow(new InsufficientBalanceException("free=100 required=420"))
                .when(balanceService)
                .freeze(eq(7L), eq(true), eq("USDT"), any());

        assertThatThrownBy(() -> txHelper.freezeBalance(order, paperAccount(), null))
                .isInstanceOf(InsufficientMarginException.class)
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("insufficient margin for PERP")
                .hasMessageContaining("initialMargin=420")
                .hasMessageContaining("USDT")
                .hasMessageContaining("free=100 required=420");

        // 余额不足,不写 frozenQuoteAmount
        verify(orderMapper, never()).updateFrozenQuoteAmount(anyLong(), any());
    }

    /** PERP CLOSE_* reduceOnly 不冻保证金,freezeBalance noop。 */
    @Test
    void freezeBalance_perpCloseLong_isNoop() {
        Order order = newPerpOrder(PositionEffect.CLOSE_LONG, OrderSide.SELL, new BigDecimal("0.1"));

        txHelper.freezeBalance(order, paperAccount(), null);

        verify(balanceService, never()).freeze(anyLong(), anyBoolean(), anyString(), any());
        verify(orderMapper, never()).updateFrozenQuoteAmount(anyLong(), any());
        assertThat(order.getFrozenQuoteAmount()).isNull();
    }

    /** PERP CLOSE_SHORT 同 CLOSE_LONG noop。 */
    @Test
    void freezeBalance_perpCloseShort_isNoop() {
        Order order = newPerpOrder(PositionEffect.CLOSE_SHORT, OrderSide.BUY, new BigDecimal("0.1"));

        txHelper.freezeBalance(order, paperAccount(), null);

        verify(balanceService, never()).freeze(anyLong(), anyBoolean(), anyString(), any());
    }

    /** PERP OPEN_* 部分成交后撤单:unfreeze 按剩余未成交比例折算 initialMargin。 */
    @Test
    void unfreezeBalance_perpOpenPartiallyFilled_releasesOnlyRemainingProportion() {
        // 冻 420 USDT,成交 30%(3/10),剩余 70% 应解冻 = 420 * 7/10 = 294
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("10"));
        order.setFilledQty(new BigDecimal("3"));
        order.setFrozenQuoteAmount(new BigDecimal("420"));

        txHelper.unfreezeBalance(order, paperAccount());

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).unfreeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("294"));
    }

    /** PERP OPEN_* 未成交撤单:unfreeze 释放全额 initialMargin。 */
    @Test
    void unfreezeBalance_perpOpenNeverFilled_releasesFullAmount() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("10"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setFrozenQuoteAmount(new BigDecimal("420"));

        txHelper.unfreezeBalance(order, paperAccount());

        verify(balanceService).unfreeze(eq(7L), eq(true), eq("USDT"), eq(new BigDecimal("420")));
    }

    /** PERP CLOSE_* reduceOnly 未冻保证金,unfreeze noop。 */
    @Test
    void unfreezeBalance_perpCloseLong_isNoop() {
        Order order = newPerpOrder(PositionEffect.CLOSE_LONG, OrderSide.SELL, new BigDecimal("0.1"));

        txHelper.unfreezeBalance(order, paperAccount());

        verify(balanceService, never()).unfreeze(anyLong(), anyBoolean(), anyString(), any());
    }

    @Test
    void unfreezeBalance_perpCloseShort_isNoop() {
        Order order = newPerpOrder(PositionEffect.CLOSE_SHORT, OrderSide.BUY, new BigDecimal("0.1"));

        txHelper.unfreezeBalance(order, paperAccount());

        verify(balanceService, never()).unfreeze(anyLong(), anyBoolean(), anyString(), any());
    }

    @Test
    void freezeBalance_liveAccount_isNoop() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("0.1"));
        ExchangeAccount liveAccount = new ExchangeAccount();
        liveAccount.setId(9L);
        liveAccount.setPaperTrading(false);

        txHelper.freezeBalance(order, liveAccount, null);

        verify(balanceService, never()).freeze(anyLong(), anyBoolean(), anyString(), any());
    }

    // ---------- JaCoCo 预存债补刀:freezeBalance SPOT 分支 + ticker fallback ----------

    /** SPOT BUY LIMIT(price!=null):用 price*qty 冻 quote,写 frozenQuoteAmount。 */
    @Test
    void freezeBalance_spotBuyLimit_freezesQuoteAndUpdatesOrder() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setOrderType(OrderType.LIMIT);

        txHelper.freezeBalance(order, paperAccount(), null);

        // 2 × 50000 = 100000 USDT(真实金额,非缩放单位)
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).freeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(order.getFrozenQuoteAmount()).isEqualByComparingTo(new BigDecimal("100000"));
        verify(orderMapper).updateFrozenQuoteAmount(eq(1L), any());
    }

    /** SPOT SELL:冻 base = qty(无 frozenQuoteAmount 写入)。 */
    @Test
    void freezeBalance_spotSell_freezesBaseQty() {
        Order order = newSpotOrder(OrderSide.SELL, new BigDecimal("2"));
        order.setOrderType(OrderType.LIMIT);

        txHelper.freezeBalance(order, paperAccount(), null);

        verify(balanceService).freeze(eq(7L), eq(true), eq("BTC"), eq(new BigDecimal("2")));
        verify(orderMapper, never()).updateFrozenQuoteAmount(anyLong(), any());
    }

    /** SPOT BUY MARKET 用 marketPrice 估算 cost(price=null, marketPrice!=null)。 */
    @Test
    void freezeBalance_spotBuyMarket_usesMarketPrice() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setOrderType(OrderType.MARKET);
        order.setPrice(null);

        txHelper.freezeBalance(order, paperAccount(), new BigDecimal("49500"));

        // 2 × 49500 = 99000 USDT
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).freeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("99000"));
    }

    /** SPOT BUY MARKET 无 marketPrice 时回退查 ticker(完整走 line 88-95 fallback)。 */
    @Test
    void freezeBalance_spotBuyMarket_fallsBackToTicker() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setOrderType(OrderType.MARKET);
        order.setPrice(null);
        when(marketDataService.getLatestTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(ticker(new BigDecimal("49000")));

        txHelper.freezeBalance(order, paperAccount(), null);

        // 2 × 49000 = 98000 USDT
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).freeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("98000"));
    }

    /** SPOT BUY MARKET 无 marketPrice 且无 ticker → 抛 InvalidOrderException。 */
    @Test
    void freezeBalance_spotBuyMarket_noTickerThrows() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setOrderType(OrderType.MARKET);
        order.setPrice(null);
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> txHelper.freezeBalance(order, paperAccount(), null))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("no ticker available");
        verify(balanceService, never()).freeze(anyLong(), anyBoolean(), anyString(), any());
    }

    /** SPOT BUY MARKET ticker 存在但 last()=null → 抛 InvalidOrderException(§line 91 last==null 分支)。 */
    @Test
    void freezeBalance_spotBuyMarket_tickerWithoutLastThrows() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setOrderType(OrderType.MARKET);
        order.setPrice(null);
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(ticker(null));

        assertThatThrownBy(() -> txHelper.freezeBalance(order, paperAccount(), null))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("no ticker available");
    }

    /** SPOT symbol 非 BASE/QUOTE 格式 → 抛 InvalidOrderException(§line 81 throw 分支)。 */
    @Test
    void freezeBalance_spotInvalidSymbol_throwsInvalidOrder() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setSymbol("BTCUSDT"); // 无 "/" 分隔

        assertThatThrownBy(() -> txHelper.freezeBalance(order, paperAccount(), null))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("invalid symbol");
    }

    // ---------- JaCoCo 预存债补刀:PERP ticker fallback + leverage 防御分支 ----------

    /** PERP OPEN_* MARKET 单无 marketPrice 时回退查 ticker(§line 128-135 fallback)。 */
    @Test
    void freezeBalance_perpOpenMarket_fallsBackToTicker() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("0.1"));
        order.setOrderType(OrderType.MARKET);
        order.setPrice(null);
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(ticker(new BigDecimal("43000")));

        txHelper.freezeBalance(order, paperAccount(), null);

        // 0.1 × 43000 / 10 = 430 USDT
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).freeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("430"));
    }

    /** PERP OPEN_* MARKET 单无 marketPrice 且无 ticker → 抛 InvalidOrderException(§line 131 throw)。 */
    @Test
    void freezeBalance_perpOpenMarket_noTickerThrows() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("0.1"));
        order.setOrderType(OrderType.MARKET);
        order.setPrice(null);
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> txHelper.freezeBalance(order, paperAccount(), null))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("no ticker available");
        verify(balanceService, never()).freeze(anyLong(), anyBoolean(), anyString(), any());
    }

    /** PERP OPEN_* leverage<=0 → 抛 InvalidOrderException(§line 139 防御性 throw)。 */
    @Test
    void freezeBalance_perpInvalidLeverage_throwsInvalidOrder() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("0.1"));
        order.setLeverage(0); // Order.validate 保证 1-125,此处二次保险

        assertThatThrownBy(() -> txHelper.freezeBalance(order, paperAccount(), null))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("leverage must be positive");
        verify(balanceService, never()).freeze(anyLong(), anyBoolean(), anyString(), any());
    }

    // ---------- JaCoCo 预存债补刀:unfreezeSpot / unfreezePerp 防御分支 ----------

    /** SPOT BUY 无 frozenQuoteAmount 且无 price/ticker → log.warn 并跳过(§line 205-211)。 */
    @Test
    void unfreezeBalance_spotBuyMarket_noFrozenAmountWarnsAndSkips() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setFilledQty(new BigDecimal("1"));
        order.setFrozenQuoteAmount(null);
        order.setPrice(null);
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(null);

        txHelper.unfreezeBalance(order, paperAccount());

        // 无 ticker 时只 log.warn 并 return,不调 unfreeze(冻结余额会留账直到手动重置)
        verify(balanceService, never()).unfreeze(anyLong(), anyBoolean(), anyString(), any());
    }

    /** SPOT BUY 无 frozenQuoteAmount 但有 ticker → 用 ticker.last()*remainingQty 兜底(§line 213-215)。 */
    @Test
    void unfreezeBalance_spotBuyMarket_noFrozenAmountUsesTicker() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setFilledQty(new BigDecimal("1")); // 剩余 1
        order.setFrozenQuoteAmount(null);
        order.setPrice(null);
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(ticker(new BigDecimal("49000")));

        txHelper.unfreezeBalance(order, paperAccount());

        // 1 × 49000 = 49000 USDT
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(balanceService).unfreeze(eq(7L), eq(true), eq("USDT"), amountCaptor.capture());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("49000"));
    }

    /** SPOT symbol 非 BASE/QUOTE 格式 → unfreeze 静默 return(§line 197 防御分支)。 */
    @Test
    void unfreezeBalance_spotInvalidSymbol_isNoop() {
        Order order = newSpotOrder(OrderSide.BUY, new BigDecimal("2"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setFrozenQuoteAmount(new BigDecimal("100"));
        order.setSymbol("BTCUSDT"); // 无 "/" 分隔

        txHelper.unfreezeBalance(order, paperAccount());

        verify(balanceService, never()).unfreeze(anyLong(), anyBoolean(), anyString(), any());
    }

    /** PERP OPEN_* 无 frozenQuoteAmount(防御性,freezeBalance 必设) → log.warn 并跳过(§line 236-243)。 */
    @Test
    void unfreezeBalance_perpOpenNoFrozenAmount_warnsAndSkips() {
        Order order = newPerpOrder(PositionEffect.OPEN_LONG, OrderSide.BUY, new BigDecimal("10"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setFrozenQuoteAmount(null); // 防御:正常不应发生

        txHelper.unfreezeBalance(order, paperAccount());

        verify(balanceService, never()).unfreeze(anyLong(), anyBoolean(), anyString(), any());
    }

    /** 构造 SPOT order(默认 price=50000, amount 由参数传)。 */
    private static Order newSpotOrder(OrderSide side, BigDecimal amount) {
        Order order = new Order();
        order.setId(1L);
        order.setExchange(Exchange.BINANCE);
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.SPOT);
        order.setSide(side);
        order.setOrderType(OrderType.LIMIT);
        order.setAmount(amount);
        order.setPrice(new BigDecimal("50000"));
        order.setFilledQty(BigDecimal.ZERO);
        return order;
    }

    /** 构造 last=last 的最小 ticker(其余字段零/now)。 */
    private static Ticker ticker(BigDecimal last) {
        return new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                last,
                null,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now(),
                Instant.now());
    }

    /** 构造 PERP order(默认 leverage=10, price=42000, amount 由参数传)。 */
    private static Order newPerpOrder(PositionEffect effect, OrderSide side, BigDecimal amount) {
        Order order = new Order();
        order.setId(1L);
        order.setSymbol("BTC/USDT");
        order.setMarketType(MarketType.PERP);
        order.setSide(side);
        order.setOrderType(com.kwikquant.shared.types.OrderType.LIMIT);
        order.setAmount(amount);
        order.setPrice(new BigDecimal("42000"));
        order.setFilledQty(BigDecimal.ZERO);
        order.setLeverage(10);
        order.setMarginMode(MarginMode.ISOLATED);
        order.setPositionEffect(effect);
        return order;
    }
}
