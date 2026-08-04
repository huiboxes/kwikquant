package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BalanceService 单元测试。验证:
 * 1. fetchBalance 按 isPaperTrading() 分流(不再看 exchange==PAPER):模拟盘委托
 *    paperBalanceAdapter.fetch,真实交易所走 CCXT factory。
 * 2. fetchBalance 实盘分支按 marketType 透传给 factory:SPOT → spot 实例,PERP → swap 实例
 *    (PERP 风控查可用保证金必须走 swap,否则拿到现货余额估值失真)。
 * 3. freeze/unfreeze/applyFill/reset 按 paperTrading 布尔参数分流;非模拟盘静默 noop
 *    (真实交易所余额由交易所维护,本地不记账);reset 非模拟盘抛 IllegalArgumentException。
 */
class BalanceServiceTest {

    private ExchangeAccountService accountService;
    private PaperBalanceAdapter paperBalanceAdapter;
    private QuoteCurrencyProperties quoteCurrencyProperties;
    // mock factory:隔离真实交易所,精确验证 createAuthExchange(account, marketType) 被调(核心:
    // PERP 必须传 PERP,不能硬编码 SPOT)。重构前用真实 factory 只能验证异常路径,无法验证 marketType 透传。
    private CcxtAuthExchangeFactory ccxtAuthExchangeFactory;
    private BalanceService balanceService;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        paperBalanceAdapter = mock(PaperBalanceAdapter.class);
        quoteCurrencyProperties = new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));
        ccxtAuthExchangeFactory = mock(CcxtAuthExchangeFactory.class);
        balanceService = new BalanceService(
                accountService, paperBalanceAdapter, quoteCurrencyProperties, ccxtAuthExchangeFactory);
    }

    // --- fetchBalance ---
    @Test
    void fetchBalance_paper_delegatesToPaperAdapter() {
        ExchangeAccount paper = new ExchangeAccount();
        paper.setId(1L);
        paper.setUserId(42L);
        paper.setExchange(Exchange.BINANCE);
        paper.setPaperTrading(true);
        when(accountService.getOwned(1L, 42L)).thenReturn(paper);
        BalanceSnapshot stub = new BalanceSnapshot(Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(
                        new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"))));
        when(paperBalanceAdapter.fetch(paper)).thenReturn(stub);

        // 两参重载(默认 SPOT)与三参显式 SPOT/PERP 都走模拟盘分支,不调 factory(单一现金桶不分市场)
        assertThat(balanceService.fetchBalance(1L, 42L)).isSameAs(stub);
        assertThat(balanceService.fetchBalance(1L, 42L, MarketType.SPOT)).isSameAs(stub);
        assertThat(balanceService.fetchBalance(1L, 42L, MarketType.PERP)).isSameAs(stub);
        verify(ccxtAuthExchangeFactory, never()).createAuthExchange(any(), any());
    }

    @Test
    void fetchBalance_real_spot_passesSpotToFactory() {
        ExchangeAccount okx = realAccount(2L);
        when(accountService.getOwned(2L, 42L)).thenReturn(okx);
        io.github.ccxt.Exchange mockExchange = mock(io.github.ccxt.Exchange.class);
        when(ccxtAuthExchangeFactory.createAuthExchange(okx, MarketType.SPOT)).thenReturn(mockExchange);
        when(mockExchange.fetchBalance())
                .thenReturn(CompletableFuture.completedFuture(
                        swapBalanceRaw(new BigDecimal("1000"), new BigDecimal("800"), new BigDecimal("200"))));

        BalanceSnapshot snap = balanceService.fetchBalance(2L, 42L); // 两参重载 → SPOT

        assertThat(snap.currencies().get("USDT").free()).isEqualByComparingTo("800");
        assertThat(snap.currencies().get("USDT").used()).isEqualByComparingTo("200");
        assertThat(snap.currencies().get("USDT").total()).isEqualByComparingTo("1000");
        verify(ccxtAuthExchangeFactory).createAuthExchange(okx, MarketType.SPOT);
        verify(paperBalanceAdapter, never()).fetch(any());
    }

    @Test
    void fetchBalance_real_perp_passesPerpToFactory() {
        ExchangeAccount okx = realAccount(2L);
        when(accountService.getOwned(2L, 42L)).thenReturn(okx);
        io.github.ccxt.Exchange mockExchange = mock(io.github.ccxt.Exchange.class);
        when(ccxtAuthExchangeFactory.createAuthExchange(okx, MarketType.PERP)).thenReturn(mockExchange);
        when(mockExchange.fetchBalance())
                .thenReturn(CompletableFuture.completedFuture(
                        swapBalanceRaw(new BigDecimal("5000"), new BigDecimal("3000"), new BigDecimal("2000"))));

        BalanceSnapshot snap = balanceService.fetchBalance(2L, 42L, MarketType.PERP);

        // PERP 保证金余额:free=可用保证金=3000,total=总权益=5000
        assertThat(snap.currencies().get("USDT").free()).isEqualByComparingTo("3000");
        assertThat(snap.currencies().get("USDT").total()).isEqualByComparingTo("5000");
        // 关键:PERP 走 swap 实例,绝不走 SPOT(否则拿到现货余额,风控估值失真)
        verify(ccxtAuthExchangeFactory).createAuthExchange(okx, MarketType.PERP);
        verify(ccxtAuthExchangeFactory, never()).createAuthExchange(eq(okx), eq(MarketType.SPOT));
    }

    @Test
    void fetchBalance_real_wrapsApiErrorAsExchangeException() {
        ExchangeAccount okx = realAccount(2L);
        when(accountService.getOwned(2L, 42L)).thenReturn(okx);
        io.github.ccxt.Exchange mockExchange = mock(io.github.ccxt.Exchange.class);
        when(ccxtAuthExchangeFactory.createAuthExchange(any(), any())).thenReturn(mockExchange);
        when(mockExchange.fetchBalance()).thenThrow(new RuntimeException("network timeout"));

        assertThatThrownBy(() -> balanceService.fetchBalance(2L, 42L))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("fetchBalance failed");
        verify(paperBalanceAdapter, never()).fetch(any(ExchangeAccount.class));
    }

    /** 构造真实(非模拟)账户 fixture。 */
    private ExchangeAccount realAccount(long id) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setUserId(42L);
        a.setExchange(Exchange.OKX);
        a.setPaperTrading(false);
        return a;
    }

    /** 构造 CCXT fetchBalance 返回的 raw Map(total/free/used 三桶),CompletableFuture 包裹(模拟基类签名)。 */
    private static Map<String, Object> swapBalanceRaw(BigDecimal total, BigDecimal free, BigDecimal used) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", Map.of("USDT", total));
        m.put("free", Map.of("USDT", free));
        m.put("used", Map.of("USDT", used));
        return m;
    }

    // --- freeze ---
    @Test
    void freeze_paper_delegatesToPaperAdapter() {
        balanceService.freeze(1L, true, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter).freeze(1L, "USDT", new BigDecimal("1000"));
    }

    @Test
    void freeze_real_isNoop() {
        balanceService.freeze(1L, false, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter, never()).freeze(anyLong(), anyString(), any(BigDecimal.class));
    }

    // --- unfreeze ---
    @Test
    void unfreeze_paper_delegatesToPaperAdapter() {
        balanceService.unfreeze(1L, true, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter).unfreeze(1L, "USDT", new BigDecimal("1000"));
    }

    @Test
    void unfreeze_real_isNoop() {
        balanceService.unfreeze(1L, false, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter, never()).unfreeze(anyLong(), anyString(), any(BigDecimal.class));
    }

    // --- applyFill ---
    @Test
    void applyFill_paper_delegatesToPaperAdapter() {
        balanceService.applyFill(new FillCommand(
                1L,
                true,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                null,
                null));

        verify(paperBalanceAdapter)
                .applyFill(
                        1L,
                        OrderSide.BUY,
                        "BTC/USDT",
                        new BigDecimal("0.1"),
                        new BigDecimal("50000"),
                        new BigDecimal("5"),
                        new BigDecimal("5000"),
                        null,
                        null);
    }

    @Test
    void applyFill_real_isNoop() {
        balanceService.applyFill(new FillCommand(
                1L,
                false,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                null,
                null));

        verify(paperBalanceAdapter, never())
                .applyFill(
                        anyLong(),
                        any(OrderSide.class),
                        anyString(),
                        any(BigDecimal.class),
                        any(BigDecimal.class),
                        any(BigDecimal.class),
                        any(),
                        any(),
                        any());
    }

    @Test
    void applyFill_paper_perpPassesMarketTypeAndPositionEffect() {
        balanceService.applyFill(new FillCommand(
                1L,
                true,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                com.kwikquant.shared.types.MarketType.PERP,
                com.kwikquant.shared.types.PositionEffect.OPEN_LONG));

        verify(paperBalanceAdapter)
                .applyFill(
                        1L,
                        OrderSide.BUY,
                        "BTC/USDT",
                        new BigDecimal("0.1"),
                        new BigDecimal("50000"),
                        new BigDecimal("5"),
                        new BigDecimal("5000"),
                        com.kwikquant.shared.types.MarketType.PERP,
                        com.kwikquant.shared.types.PositionEffect.OPEN_LONG);
    }

    // --- applyPnlSettlement ---
    @Test
    void applyPnlSettlement_paper_delegatesToPaperAdapter() {
        balanceService.applyPnlSettlement(1L, true, "USDT", new BigDecimal("500"));

        verify(paperBalanceAdapter).applyPnlSettlement(1L, "USDT", new BigDecimal("500"));
    }

    @Test
    void applyPnlSettlement_real_isNoop() {
        balanceService.applyPnlSettlement(1L, false, "USDT", new BigDecimal("500"));

        verify(paperBalanceAdapter, never()).applyPnlSettlement(anyLong(), anyString(), any(BigDecimal.class));
    }

    // --- applyLiquidationDelta ---
    @Test
    void applyLiquidationDelta_paper_delegatesToPaperAdapter() {
        balanceService.applyLiquidationDelta(1L, true, "USDT", new BigDecimal("-500"), new BigDecimal("-500"));

        verify(paperBalanceAdapter).applyLiquidationDelta(1L, "USDT", new BigDecimal("-500"), new BigDecimal("-500"));
    }

    @Test
    void applyLiquidationDelta_real_isNoop() {
        balanceService.applyLiquidationDelta(1L, false, "USDT", new BigDecimal("-500"), new BigDecimal("-500"));

        verify(paperBalanceAdapter, never())
                .applyLiquidationDelta(anyLong(), anyString(), any(BigDecimal.class), any(BigDecimal.class));
    }

    // --- reset ---
    @Test
    void reset_paper_delegatesToPaperAdapter() {
        balanceService.reset(1L, true);

        verify(paperBalanceAdapter).reset(1L, "USDT");
    }

    @Test
    void reset_real_throwsIllegalArgument() {
        assertThatThrownBy(() -> balanceService.reset(1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paper");
        verify(paperBalanceAdapter, never()).reset(anyLong(), anyString());
    }
}
