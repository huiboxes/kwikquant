package com.kwikquant.mcp.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.ExchangeAccountService.ExchangeAccountView;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.mcp.interfaces.view.BalanceSnapshotView;
import com.kwikquant.mcp.interfaces.view.McpExchangeAccountView;
import com.kwikquant.mcp.interfaces.view.PortfolioSummaryView;
import com.kwikquant.mcp.interfaces.view.TradeHistoryPageView;
import com.kwikquant.report.application.PortfolioService;
import com.kwikquant.report.application.PortfolioService.AccountSummary;
import com.kwikquant.report.application.PortfolioService.PortfolioSummary;
import com.kwikquant.report.application.TradeHistoryService;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryItem;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryStats;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link AccountTools} 单测。验证：list_accounts 剥离 apiKey、get_balances 前置 getOwned、
 * get_portfolio 透传、get_trade_history 合并 query+stats + since/until 解析 + 前置 getOwned。
 */
class AccountToolsTest {

    private ExchangeAccountService accountService;
    private BalanceService balanceService;
    private PortfolioService portfolioService;
    private TradeHistoryService tradeHistoryService;
    private AccountTools tools;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        portfolioService = mock(PortfolioService.class);
        tradeHistoryService = mock(TradeHistoryService.class);
        tools = new AccountTools(accountService, balanceService, portfolioService, tradeHistoryService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAccounts_returnsViewsWithoutApiKey() {
        ExchangeAccountView v =
                new ExchangeAccountView(1L, Exchange.BINANCE, "main", "sec-ret-key-1234", true, "ACTIVE");
        when(accountService.listByUser(42L)).thenReturn(List.of(v));

        List<McpExchangeAccountView> result = tools.listAccounts();

        assertThat(result).hasSize(1);
        McpExchangeAccountView m = result.get(0);
        assertThat(m.id()).isEqualTo(1L);
        assertThat(m.exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(m.label()).isEqualTo("main");
        assertThat(m.paperTrading()).isTrue();
        assertThat(m.status()).isEqualTo("ACTIVE");
        // 严格确认 McpExchangeAccountView 无 apiKey 字段
        assertThat(m.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("apiKey");
    }

    @Test
    void getBalances_valid_delegates() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        BalanceSnapshot snapshot = new BalanceSnapshot(Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"))));
        when(balanceService.fetchBalance(1L, 42L)).thenReturn(snapshot);

        BalanceSnapshotView result = tools.getBalances(1L);

        assertThat(result.currencies()).containsKey("USDT");
        assertThat(result.currencies().get("USDT").total()).isEqualByComparingTo("1000");
        verify(accountService).getOwned(1L, 42L);
        verify(balanceService).fetchBalance(1L, 42L);
    }

    @Test
    void getBalances_accountNotOwned_throws1002() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.getBalances(99L)).isInstanceOf(OwnershipViolationException.class);
    }

    @Test
    void getPortfolio_delegates() {
        PortfolioSummary summary = new PortfolioSummary(
                List.of(new AccountSummary(1L, Exchange.BINANCE, "main", List.of(), new BigDecimal("5000"))),
                new BigDecimal("5000"));
        when(portfolioService.getSummary(42L, null)).thenReturn(summary);

        PortfolioSummaryView result = tools.getPortfolio();

        assertThat(result.totalUsdt()).isEqualByComparingTo("5000");
        assertThat(result.accounts()).hasSize(1);
        assertThat(result.accounts().get(0).exchange()).isEqualTo("BINANCE");
        verify(portfolioService).getSummary(42L, null);
    }

    @Test
    void getTradeHistory_valid_returnsPageWithStats() {
        when(accountService.getOwned(1L, 42L)).thenReturn(exchangeAccount(1L, 42L));
        TradeHistoryItem item = new TradeHistoryItem(
                10L,
                1L,
                "BTC/USDT",
                "BUY",
                "MARKET",
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("0.001"),
                new BigDecimal("5000"),
                "FILLED",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:05Z"));
        PageDto<TradeHistoryItem> page = new PageDto<>(List.of(item), 1, 20, 1L, 1);
        when(tradeHistoryService.query(
                        eq(42L),
                        eq(1L),
                        eq("BTC/USDT"),
                        eq(Instant.parse("2024-01-01T00:00:00Z")),
                        eq(Instant.parse("2024-06-01T00:00:00Z")),
                        any(PageQuery.class)))
                .thenReturn(page);
        TradeHistoryStats stats =
                new TradeHistoryStats(new BigDecimal("5000"), new BigDecimal("0.001"), new BigDecimal("100"), 5, new BigDecimal("0.6000"));
        when(tradeHistoryService.stats(eq(42L), eq(1L), eq(Instant.parse("2024-01-01T00:00:00Z")), isNull()))
                .thenReturn(stats);

        TradeHistoryPageView result =
                tools.getTradeHistory(1L, "BTC/USDT", "2024-01-01T00:00:00Z", "2024-06-01T00:00:00Z", 1, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.stats().totalVolume()).isEqualByComparingTo("5000");
    }

    @Test
    void getTradeHistory_accountNotOwned_throws1002() {
        when(accountService.getOwned(eq(99L), eq(42L))).thenThrow(new OwnershipViolationException("exchange_account"));

        assertThatThrownBy(() -> tools.getTradeHistory(99L, null, null, null, null, null))
                .isInstanceOf(OwnershipViolationException.class);
    }

    @Test
    void getTradeHistory_invalidSince_throws10002() {
        assertThatThrownBy(() -> tools.getTradeHistory(null, null, "not-a-date", null, null, null))
                .isInstanceOf(McpToolParamInvalidException.class)
                .hasMessageContaining("since");
    }

    @Test
    void getTradeHistory_nullParams_defaultsApplied() {
        PageDto<TradeHistoryItem> page = new PageDto<>(List.of(), 1, 20, 0L, 0);
        when(tradeHistoryService.query(eq(42L), isNull(), isNull(), isNull(), isNull(), any(PageQuery.class)))
                .thenReturn(page);
        when(tradeHistoryService.stats(eq(42L), isNull(), isNull(), isNull()))
                .thenReturn(new TradeHistoryStats(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, null));

        TradeHistoryPageView result = tools.getTradeHistory(null, null, null, null, null, null);

        assertThat(result.items()).isEmpty();
        verify(tradeHistoryService)
                .query(
                        eq(42L),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argThat(pq -> pq.page() == 1 && pq.pageSize() == 20));
    }

    private static ExchangeAccount exchangeAccount(long id, long userId) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setUserId(userId);
        a.setExchange(Exchange.BINANCE);
        return a;
    }
}
