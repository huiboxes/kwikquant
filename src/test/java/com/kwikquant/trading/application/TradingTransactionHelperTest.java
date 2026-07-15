package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * H3 回归测试：BUY 单部分成交后撤单/GTD 过期，unfreezeBalance 必须按剩余未成交比例折算，
 * 而不是用下单时冻结的原始全额（否则已成交部分对应的冻结额会被重复释放，虚增可用余额）。
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
}
