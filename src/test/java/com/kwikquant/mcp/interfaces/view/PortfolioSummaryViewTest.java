package com.kwikquant.mcp.interfaces.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kwikquant.report.application.PortfolioService.AccountSummary;
import com.kwikquant.report.application.PortfolioService.CurrencyBalanceWithUsdt;
import com.kwikquant.report.application.PortfolioService.PortfolioSummary;
import com.kwikquant.shared.types.Exchange;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PortfolioSummaryView 投影链单测:3 层 record + from() + null 兜底分支(MCP get_portfolio view 的
 * 投影正确性,剥离 service 内部字段)。
 */
class PortfolioSummaryViewTest {

    @Test
    void from_nullPortfolioSummary_returnsEmptyAccounts() {
        assertThat(PortfolioSummaryView.from(null).accounts()).isEmpty();
    }

    @Test
    void from_validPortfolioSummary_mapsAllAccounts() {
        CurrencyBalanceWithUsdt bal = new CurrencyBalanceWithUsdt(
                "USDT", new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("100"));
        AccountSummary acct = new AccountSummary(1L, Exchange.BINANCE, "main", List.of(bal), new BigDecimal("100"));
        PortfolioSummary summary = new PortfolioSummary(List.of(acct));

        PortfolioSummaryView view = PortfolioSummaryView.from(summary);

        assertThat(view.accounts()).hasSize(1);
        PortfolioSummaryView.AccountSummaryView a = view.accounts().get(0);
        assertThat(a.accountId()).isEqualTo(1L);
        assertThat(a.exchange()).isEqualTo("BINANCE");
        assertThat(a.label()).isEqualTo("main");
        assertThat(a.totalUsdt()).isEqualByComparingTo("100");
        assertThat(a.balances()).hasSize(1);
        PortfolioSummaryView.CurrencyBalanceWithUsdtView b = a.balances().get(0);
        assertThat(b.currency()).isEqualTo("USDT");
        assertThat(b.free()).isEqualByComparingTo("100");
        assertThat(b.usdtValue()).isEqualByComparingTo("100");
    }

    @Test
    void from_accountWithNullExchange_returnsNullExchange() {
        AccountSummary acct = new AccountSummary(1L, null, "main", List.of(), BigDecimal.ZERO);
        PortfolioSummary summary = new PortfolioSummary(List.of(acct));

        PortfolioSummaryView.AccountSummaryView a =
                PortfolioSummaryView.from(summary).accounts().get(0);

        assertThat(a.exchange()).isNull();
    }

    @Test
    void from_accountWithNullBalances_returnsEmptyList() {
        AccountSummary acct = new AccountSummary(1L, Exchange.BINANCE, "main", null, BigDecimal.ZERO);
        PortfolioSummary summary = new PortfolioSummary(List.of(acct));

        PortfolioSummaryView.AccountSummaryView a =
                PortfolioSummaryView.from(summary).accounts().get(0);

        assertThat(a.balances()).isEmpty();
    }

    @Test
    void from_nullAccountSummary_returnsNullFieldsAndEmptyBalances() {
        PortfolioSummaryView.AccountSummaryView a = PortfolioSummaryView.AccountSummaryView.from(null);

        assertThat(a.accountId()).isNull();
        assertThat(a.exchange()).isNull();
        assertThat(a.label()).isNull();
        assertThat(a.balances()).isEmpty();
        assertThat(a.totalUsdt()).isNull();
    }

    @Test
    void from_nullCurrencyBalance_returnsAllNulls() {
        PortfolioSummaryView.CurrencyBalanceWithUsdtView b =
                PortfolioSummaryView.CurrencyBalanceWithUsdtView.from(null);

        assertThat(b.currency()).isNull();
        assertThat(b.free()).isNull();
        assertThat(b.used()).isNull();
        assertThat(b.total()).isNull();
        assertThat(b.usdtValue()).isNull();
    }

    @Test
    void from_validCurrencyBalance_mapsAllFields() {
        CurrencyBalanceWithUsdt c = new CurrencyBalanceWithUsdt(
                "BTC", new BigDecimal("1"), new BigDecimal("0.5"), new BigDecimal("1.5"), new BigDecimal("50000"));

        PortfolioSummaryView.CurrencyBalanceWithUsdtView b = PortfolioSummaryView.CurrencyBalanceWithUsdtView.from(c);

        assertThat(b.currency()).isEqualTo("BTC");
        assertThat(b.free()).isEqualByComparingTo("1");
        assertThat(b.used()).isEqualByComparingTo("0.5");
        assertThat(b.total()).isEqualByComparingTo("1.5");
        assertThat(b.usdtValue()).isEqualByComparingTo("50000");
    }

    @Test
    void from_balancesIsImmutableList() {
        CurrencyBalanceWithUsdt bal =
                new CurrencyBalanceWithUsdt("USDT", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        AccountSummary acct = new AccountSummary(1L, Exchange.BINANCE, "main", List.of(bal), BigDecimal.ONE);
        PortfolioSummary summary = new PortfolioSummary(List.of(acct));

        List<PortfolioSummaryView.CurrencyBalanceWithUsdtView> balances =
                PortfolioSummaryView.from(summary).accounts().get(0).balances();

        // toUnmodifiableList → 不可变,add 抛 UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () -> balances.add(null));
    }
}
