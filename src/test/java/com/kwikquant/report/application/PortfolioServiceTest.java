package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.application.PositionService;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class PortfolioServiceTest {

    private ExchangeAccountService accountService;
    private BalanceService balanceService;
    private MarketDataService marketDataService;
    private PositionService positionService;
    private SimpMessagingTemplate messagingTemplate;
    private PortfolioService service;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        marketDataService = mock(MarketDataService.class);
        positionService = mock(PositionService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new PortfolioService(
                accountService, balanceService, marketDataService, positionService, messagingTemplate);
    }

    @Test
    void getSummary_twoAccounts_correctUsdtValuation() {
        ExchangeAccountView acct1 = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, "ACTIVE");
        ExchangeAccountView acct2 = new ExchangeAccountView(2L, Exchange.OKX, "sub", "k2", false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct1, acct2));

        BalanceSnapshot snap1 = new BalanceSnapshot(Map.of(
                "BTC",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("1")),
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"))));
        when(balanceService.fetchBalance(eq(1L), eq(42L))).thenReturn(snap1);

        BalanceSnapshot snap2 = new BalanceSnapshot(Map.of(
                "ETH",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"))));
        when(balanceService.fetchBalance(eq(2L), eq(42L))).thenReturn(snap2);

        Instant now = Instant.now();
        when(marketDataService.getLatestTicker(eq(Exchange.BINANCE), eq(MarketType.SPOT), eq("BTC/USDT")))
                .thenReturn(ticker(Exchange.BINANCE, "BTC/USDT", "50000", now));
        when(marketDataService.getLatestTicker(eq(Exchange.OKX), eq(MarketType.SPOT), eq("ETH/USDT")))
                .thenReturn(ticker(Exchange.OKX, "ETH/USDT", "3000", now));

        PortfolioService.PortfolioSummary summary = service.getSummary(42L);

        assertThat(summary.accounts()).hasSize(2);
        // Total = 50000 (1 BTC) + 100 (USDT) + 30000 (10 ETH) = 80100
        assertThat(summary.totalUsdt()).isEqualByComparingTo(new BigDecimal("80100"));
    }

    @Test
    void getSummary_singleAccountFails_gracefulDegradation() {
        ExchangeAccountView acct1 = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, "ACTIVE");
        ExchangeAccountView acct2 = new ExchangeAccountView(2L, Exchange.OKX, "sub", "k2", false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct1, acct2));

        when(balanceService.fetchBalance(eq(1L), eq(42L))).thenThrow(new ExchangeException("timeout", true));

        BalanceSnapshot snap2 = new BalanceSnapshot(Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("500"), BigDecimal.ZERO, new BigDecimal("500"))));
        when(balanceService.fetchBalance(eq(2L), eq(42L))).thenReturn(snap2);

        PortfolioService.PortfolioSummary summary = service.getSummary(42L);

        assertThat(summary.accounts()).hasSize(1);
        assertThat(summary.totalUsdt()).isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    void getSummary_allAccountsFail_throwsExchangeException() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct));
        when(balanceService.fetchBalance(eq(1L), eq(42L))).thenThrow(new ExchangeException("fail", true));

        assertThatThrownBy(() -> service.getSummary(42L))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("all exchange accounts failed");
    }

    @Test
    void getPnl_longPosition_unrealizedPnlCorrect() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct));

        Position pos = new Position();
        pos.setAccountId(1L);
        pos.setSymbol("BTC/USDT");
        pos.setSide(Position.SIDE_LONG);
        pos.setQty(new BigDecimal("1"));
        pos.setAvgEntryPrice(new BigDecimal("100"));
        pos.setRealizedPnl(new BigDecimal("5"));
        when(positionService.findByAccount(1L)).thenReturn(List.of(pos));

        Instant now = Instant.now();
        when(marketDataService.getLatestTicker(eq(Exchange.BINANCE), eq(MarketType.SPOT), eq("BTC/USDT")))
                .thenReturn(ticker(Exchange.BINANCE, "BTC/USDT", "110", now));

        PortfolioService.PortfolioPnl pnl = service.getPnl(42L);

        // unrealizedPnl = (110 - 100) * 1 = 10
        assertThat(pnl.totalUnrealizedPnl()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(pnl.positions()).hasSize(1);
        assertThat(pnl.positions().getFirst().unrealizedPnl()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void getSummary_paperAccountSkipped() {
        ExchangeAccountView paper = new ExchangeAccountView(1L, Exchange.PAPER, "paper", "k1", true, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(paper));

        PortfolioService.PortfolioSummary summary = service.getSummary(42L);

        assertThat(summary.accounts()).isEmpty();
        assertThat(summary.totalUsdt()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(balanceService, never()).fetchBalance(anyLong(), anyLong());
    }

    // ---------- helpers ----------

    private static Ticker ticker(Exchange exchange, String symbol, String price, Instant now) {
        return new Ticker(
                exchange,
                MarketType.SPOT,
                symbol,
                new BigDecimal(price),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now);
    }
}
