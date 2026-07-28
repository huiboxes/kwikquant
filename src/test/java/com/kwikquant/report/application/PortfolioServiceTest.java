package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.kwikquant.shared.infra.PortfolioSubscriptionRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class PortfolioServiceTest {

    private ExchangeAccountService accountService;
    private BalanceService balanceService;
    private MarketDataService marketDataService;
    private PositionService positionService;
    private SimpMessagingTemplate messagingTemplate;
    private JdbcTemplate jdbcTemplate;
    private PortfolioSubscriptionRegistry portfolioSubscriptionRegistry;
    private PortfolioService service;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        marketDataService = mock(MarketDataService.class);
        positionService = mock(PositionService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        portfolioSubscriptionRegistry = mock(PortfolioSubscriptionRegistry.class);
        service = new PortfolioService(
                accountService,
                balanceService,
                marketDataService,
                positionService,
                messagingTemplate,
                jdbcTemplate,
                portfolioSubscriptionRegistry);
    }

    @Test
    void snapshotEquity_noUsers_skipsInsert() {
        when(jdbcTemplate.queryForList("SELECT DISTINCT user_id FROM exchange_accounts", Long.class))
                .thenReturn(List.of());
        service.snapshotEquity();
        verify(jdbcTemplate, never())
                .update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void getSummary_twoAccounts_correctUsdtValuation() {
        ExchangeAccountView acct1 = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
        ExchangeAccountView acct2 = new ExchangeAccountView(2L, Exchange.OKX, "sub", "k2", false, false, "ACTIVE");
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

        PortfolioService.PortfolioSummary summary = service.getSummary(42L, null);

        assertThat(summary.accounts()).hasSize(2);
    }

    @Test
    void getSummary_singleAccountFails_gracefulDegradation() {
        ExchangeAccountView acct1 = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
        ExchangeAccountView acct2 = new ExchangeAccountView(2L, Exchange.OKX, "sub", "k2", false, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct1, acct2));

        when(balanceService.fetchBalance(eq(1L), eq(42L))).thenThrow(new ExchangeException("timeout", true));

        BalanceSnapshot snap2 = new BalanceSnapshot(Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("500"), BigDecimal.ZERO, new BigDecimal("500"))));
        when(balanceService.fetchBalance(eq(2L), eq(42L))).thenReturn(snap2);

        PortfolioService.PortfolioSummary summary = service.getSummary(42L, null);

        assertThat(summary.accounts()).hasSize(1);
    }

    @Test
    void getSummary_allAccountsFail_throwsExchangeException() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct));
        when(balanceService.fetchBalance(eq(1L), eq(42L))).thenThrow(new ExchangeException("fail", true));

        assertThatThrownBy(() -> service.getSummary(42L, null))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("all exchange accounts failed");
    }

    /**
     * 模拟盘账户不计入组合总额（避免模拟资金和真实资金混算误导使用者）。排除依据是
     * paperTrading=true，不再依赖 exchange 枚举值（建号已禁止 exchange=PAPER）。
     */
    @Test
    void getSummary_paperAccountExcluded() {
        ExchangeAccountView paperAcct =
                new ExchangeAccountView(3L, Exchange.BINANCE, "paper", null, true, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(paperAcct));

        PortfolioService.PortfolioSummary summary = service.getSummary(42L, null);

        assertThat(summary.accounts()).isEmpty();
        verify(balanceService, never()).fetchBalance(anyLong(), anyLong());
    }

    @Test
    void getPnl_paperAccountExcluded() {
        ExchangeAccountView paperAcct =
                new ExchangeAccountView(3L, Exchange.BINANCE, "paper", null, true, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(paperAcct));

        PortfolioService.PortfolioPnl pnl = service.getPnl(42L, null);

        assertThat(pnl.positions()).isEmpty();
        assertThat(pnl.totalUnrealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(positionService, never()).findByAccount(anyLong());
    }

    @Test
    void getPnl_longPosition_unrealizedPnlCorrect() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
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

        PortfolioService.PortfolioPnl pnl = service.getPnl(42L, null);

        // unrealizedPnl = (110 - 100) * 1 = 10
        assertThat(pnl.totalUnrealizedPnl()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(pnl.positions()).hasSize(1);
        assertThat(pnl.positions().getFirst().unrealizedPnl()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    void getPnl_shortPosition_unrealizedPnlCorrect() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct));
        Position pos = new Position();
        pos.setAccountId(1L);
        pos.setSymbol("BTC/USDT");
        pos.setSide(Position.SIDE_SHORT);
        pos.setQty(new BigDecimal("1"));
        pos.setAvgEntryPrice(new BigDecimal("100"));
        pos.setRealizedPnl(BigDecimal.ZERO);
        when(positionService.findByAccount(1L)).thenReturn(List.of(pos));
        Instant now = Instant.now();
        when(marketDataService.getLatestTicker(eq(Exchange.BINANCE), eq(MarketType.SPOT), eq("BTC/USDT")))
                .thenReturn(ticker(Exchange.BINANCE, "BTC/USDT", "90", now));

        PortfolioService.PortfolioPnl pnl = service.getPnl(42L, null);

        // SHORT: (avgEntry 100 - current 90) * 1 = 10
        assertThat(pnl.totalUnrealizedPnl()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(pnl.positions()).hasSize(1);
    }

    @Test
    void getPnl_flatPosition_skipped() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct));
        Position pos = new Position();
        pos.setAccountId(1L);
        pos.setSymbol("BTC/USDT");
        pos.setSide(Position.SIDE_LONG);
        pos.setQty(BigDecimal.ZERO); // flat → skip
        pos.setAvgEntryPrice(new BigDecimal("100"));
        pos.setRealizedPnl(BigDecimal.ZERO);
        when(positionService.findByAccount(1L)).thenReturn(List.of(pos));

        PortfolioService.PortfolioPnl pnl = service.getPnl(42L, null);

        assertThat(pnl.positions()).isEmpty();
        assertThat(pnl.totalUnrealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getPnl_currentPriceNull_skipped() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct));
        Position pos = new Position();
        pos.setAccountId(1L);
        pos.setSymbol("BTC/USDT");
        pos.setSide(Position.SIDE_LONG);
        pos.setQty(new BigDecimal("1"));
        pos.setAvgEntryPrice(new BigDecimal("100"));
        pos.setRealizedPnl(BigDecimal.ZERO);
        when(positionService.findByAccount(1L)).thenReturn(List.of(pos));
        // getCurrentPrice: SPOT null + PERP null → null → skip(覆盖 SPOT fallback PERP 分支)
        when(marketDataService.getLatestTicker(eq(Exchange.BINANCE), eq(MarketType.SPOT), eq("BTC/USDT")))
                .thenReturn(null);
        when(marketDataService.getLatestTicker(eq(Exchange.BINANCE), eq(MarketType.PERP), eq("BTC/USDT")))
                .thenReturn(null);

        PortfolioService.PortfolioPnl pnl = service.getPnl(42L, null);

        assertThat(pnl.positions()).isEmpty();
        assertThat(pnl.totalUnrealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------- getEquityCurve(权益曲线:历史查询 + 兜底)----------

    @Test
    void getEquityCurve_historyPresent_returnsHistory() {
        PortfolioService.EquitySnapshot snap =
                new PortfolioService.EquitySnapshot(Instant.now(), new BigDecimal("500"));
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(snap));

        List<PortfolioService.EquitySnapshot> result = service.getEquityCurve(42L, 7, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).equity()).isEqualByComparingTo("500");
    }

    @Test
    void getEquityCurve_emptyHistory_fallsBackToCurrentSnapshot() {
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.Collections.emptyList());
        when(accountService.listByUser(42L)).thenReturn(List.of());

        List<PortfolioService.EquitySnapshot> result = service.getEquityCurve(42L, 7, null);

        // 兜底 currentEquitySnapshot:空账户 equity=0,返 2 点水平线
        assertThat(result).hasSize(2);
        assertThat(result.get(0).equity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getEquityCurve_queryThrows_fallsBackToCurrentSnapshot() {
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));
        when(accountService.listByUser(42L)).thenReturn(List.of());

        List<PortfolioService.EquitySnapshot> result = service.getEquityCurve(42L, 7, null);

        assertThat(result).hasSize(2);
    }

    // ---------- snapshotEquity(定时快照:per-user 失败不阻断循环)----------

    @Test
    void snapshotEquity_perUserFailure_doesNotBreakLoop() {
        // 2 用户:42 抛异常 → catch per-user → 43 仍处理
        when(jdbcTemplate.queryForList("SELECT DISTINCT user_id FROM exchange_accounts", Long.class))
                .thenReturn(List.of(42L, 43L));
        when(accountService.listByUser(42L)).thenThrow(new RuntimeException("42 fail"));
        when(accountService.listByUser(43L)).thenReturn(List.of());

        // 不抛(per-user catch)
        org.assertj.core.api.Assertions.assertThatCode(() -> service.snapshotEquity())
                .doesNotThrowAnyException();

        verify(accountService, org.mockito.Mockito.atLeastOnce()).listByUser(42L);
        verify(accountService, org.mockito.Mockito.atLeastOnce()).listByUser(43L);
    }

    // ---------- pushUpdate(单用户推送 + 异常吞)----------

    @Test
    void pushUpdate_sendsSummaryToTopic() {
        ExchangeAccountView acct = new ExchangeAccountView(1L, Exchange.BINANCE, "main", "k1", false, false, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(acct));
        when(balanceService.fetchBalance(eq(1L), eq(42L))).thenReturn(new BalanceSnapshot(Map.of()));

        service.pushUpdate(42L);

        verify(messagingTemplate)
                .convertAndSend(eq("/topic/portfolio/42"), any(PortfolioService.PortfolioSummary.class));
    }

    @Test
    void pushUpdate_getSummaryThrows_swallowsException() {
        when(accountService.listByUser(42L)).thenThrow(new RuntimeException("boom"));

        // pushUpdate catch 吞,不冒泡到 scheduledPush
        org.assertj.core.api.Assertions.assertThatCode(() -> service.pushUpdate(42L))
                .doesNotThrowAnyException();
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(PortfolioService.PortfolioSummary.class));
    }

    // ---------- scheduledPush(接线:遍历 registry activeUserIds)----------

    @Test
    void scheduledPush_iteratesActiveUserIdsAndPushesEach() {
        when(portfolioSubscriptionRegistry.activeUserIds()).thenReturn(java.util.Set.of(42L, 43L));
        when(accountService.listByUser(anyLong())).thenReturn(List.of());

        service.scheduledPush();

        verify(portfolioSubscriptionRegistry).activeUserIds();
        // 每用户 pushUpdate→getSummary→listByUser,2 用户调 2 次
        verify(accountService, org.mockito.Mockito.times(2)).listByUser(anyLong());
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
