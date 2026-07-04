package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.report.application.PortfolioService.AccountSummary;
import com.kwikquant.report.application.PortfolioService.CurrencyBalanceWithUsdt;
import com.kwikquant.report.application.PortfolioService.PortfolioSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP {@code get_portfolio} 工具返回的组合投影。剥离 service 层 {@link PortfolioSummary} 的未来内部字段，
 * 暴露 accounts（{@link AccountSummaryView}）+ totalUsdt。模块边界隔离。
 */
public record PortfolioSummaryView(List<AccountSummaryView> accounts, BigDecimal totalUsdt) {
    public static PortfolioSummaryView from(PortfolioSummary s) {
        if (s == null) {
            return new PortfolioSummaryView(List.of(), BigDecimal.ZERO);
        }
        return new PortfolioSummaryView(
                s.accounts().stream().map(AccountSummaryView::from).collect(Collectors.toUnmodifiableList()),
                s.totalUsdt());
    }

    public record AccountSummaryView(
            Long accountId,
            String exchange,
            String label,
            List<CurrencyBalanceWithUsdtView> balances,
            BigDecimal totalUsdt) {
        public static AccountSummaryView from(AccountSummary a) {
            if (a == null) {
                return new AccountSummaryView(null, null, null, List.of(), null);
            }
            return new AccountSummaryView(
                    a.accountId(),
                    a.exchange() != null ? a.exchange().name() : null,
                    a.label(),
                    a.balances() == null
                            ? List.of()
                            : a.balances().stream()
                                    .map(CurrencyBalanceWithUsdtView::from)
                                    .collect(Collectors.toUnmodifiableList()),
                    a.totalUsdt());
        }
    }

    public record CurrencyBalanceWithUsdtView(
            String currency, BigDecimal free, BigDecimal used, BigDecimal total, BigDecimal usdtValue) {
        public static CurrencyBalanceWithUsdtView from(CurrencyBalanceWithUsdt c) {
            if (c == null) {
                return new CurrencyBalanceWithUsdtView(null, null, null, null, null);
            }
            return new CurrencyBalanceWithUsdtView(c.currency(), c.free(), c.used(), c.total(), c.usdtValue());
        }
    }
}
