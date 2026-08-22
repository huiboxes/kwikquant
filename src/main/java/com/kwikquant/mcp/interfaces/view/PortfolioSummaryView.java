package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.report.application.PortfolioService.AccountSummary;
import com.kwikquant.report.application.PortfolioService.CurrencyBalanceWithUsdt;
import com.kwikquant.report.application.PortfolioService.PortfolioSummary;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP {@code get_portfolio} 工具返回的组合投影。剥离 service 层 {@link PortfolioSummary} 的未来内部字段，
 * 暴露 accounts（{@link AccountSummaryView}）。模块边界隔离(顶层 totalUsdt 已清,前端按"可用资金"reduce accounts USDT total)。
 * 金额红线：余额/估值一律字符串输出。
 */
public record PortfolioSummaryView(List<AccountSummaryView> accounts) {
    public static PortfolioSummaryView from(PortfolioSummary s) {
        if (s == null) {
            return new PortfolioSummaryView(List.of());
        }
        return new PortfolioSummaryView(
                s.accounts().stream().map(AccountSummaryView::from).collect(Collectors.toUnmodifiableList()));
    }

    /** paperTrading 是模拟/实盘的唯一判定依据(exchange 仅表示接入的交易所,不承载模式语义)。 */
    public record AccountSummaryView(
            Long accountId,
            String exchange,
            boolean paperTrading,
            String label,
            List<CurrencyBalanceWithUsdtView> balances,
            String totalUsdt) {
        public static AccountSummaryView from(AccountSummary a) {
            if (a == null) {
                return new AccountSummaryView(null, null, false, null, List.of(), null);
            }
            return new AccountSummaryView(
                    a.accountId(),
                    a.exchange() != null ? a.exchange().name() : null,
                    a.paperTrading(),
                    a.label(),
                    a.balances() == null
                            ? List.of()
                            : a.balances().stream()
                                    .map(CurrencyBalanceWithUsdtView::from)
                                    .collect(Collectors.toUnmodifiableList()),
                    str(a.totalUsdt()));
        }
    }

    public record CurrencyBalanceWithUsdtView(
            String currency, String free, String used, String total, String usdtValue) {
        public static CurrencyBalanceWithUsdtView from(CurrencyBalanceWithUsdt c) {
            if (c == null) {
                return new CurrencyBalanceWithUsdtView(null, null, null, null, null);
            }
            return new CurrencyBalanceWithUsdtView(
                    c.currency(), str(c.free()), str(c.used()), str(c.total()), str(c.usdtValue()));
        }
    }
}
