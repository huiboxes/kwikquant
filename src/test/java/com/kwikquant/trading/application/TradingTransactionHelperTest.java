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

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.InsufficientMarginException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
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
