package com.kwikquant.mcp.interfaces;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.mcp.interfaces.view.BalanceSnapshotView;
import com.kwikquant.mcp.interfaces.view.McpExchangeAccountView;
import com.kwikquant.mcp.interfaces.view.PortfolioSummaryView;
import com.kwikquant.mcp.interfaces.view.TradeHistoryPageView;
import com.kwikquant.report.application.PortfolioService;
import com.kwikquant.report.application.TradeHistoryService;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryItem;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryStats;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 账户工具组（§3.6）。4 个 {@code @McpTool}：list_accounts / get_balances / get_portfolio / get_trade_history。
 * 全查询型，不写表。
 *
 * <p><b>list_accounts 剥离 apiKey</b>：{@link ExchangeAccountService.ExchangeAccountView} 含 apiKey 明文字段，
 * MCP 工具层映射到 {@link McpExchangeAccountView}（不含 apiKey），防泄露给 Agent。不给 service View 加
 * @JsonIgnore（会影响 REST 现有行为）。
 *
 * <p><b>get_balances/get_trade_history 前置 getOwned</b>：{@link BalanceService#fetchBalance} /
 * {@link TradeHistoryService#query} 跨账户查询，工具层前置 {@link ExchangeAccountService#getOwned} 校验
 * accountId 属当前用户（不通过抛 1002）。get_trade_history 的 accountId 可省略（查全部账户）。
 *
 * <p>get_balances 交易所 API 失败抛 {@link com.kwikquant.shared.infra.ExchangeException}（6001）透传。
 * get_portfolio 无账户时 service 返空 summary（totalUsdt=0）兜底。
 *
 * <p>get_trade_history 的 since/until 是 ISO-8601 String，工具层 {@code Instant.parse} 转 Instant（非法抛 10002）。
 */
@Component
public class AccountTools {

    private final ExchangeAccountService accountService;
    private final BalanceService balanceService;
    private final PortfolioService portfolioService;
    private final TradeHistoryService tradeHistoryService;

    public AccountTools(
            ExchangeAccountService accountService,
            BalanceService balanceService,
            PortfolioService portfolioService,
            TradeHistoryService tradeHistoryService) {
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.portfolioService = portfolioService;
        this.tradeHistoryService = tradeHistoryService;
    }

    @McpTool(name = "list_accounts", description = "列出已连接的交易所账户(不含apiKey). 返回 id/exchange/label/paperTrading/status.")
    public List<McpExchangeAccountView> listAccounts() {
        long userId = SecurityUtils.currentUserId();
        return accountService.listByUser(userId).stream()
                .map(McpExchangeAccountView::from)
                .toList();
    }

    @McpTool(name = "get_balances", description = "查指定账户实时余额. accountId 须属当前PAT用户, 否则 1002. 交易所API失败抛 6001.")
    public BalanceSnapshotView getBalances(@McpToolParam(description = "交易所账户ID") Long accountId) {
        long userId = SecurityUtils.currentUserId();
        accountService.getOwned(accountId, userId);
        return BalanceSnapshotView.from(balanceService.fetchBalance(accountId, userId));
    }

    @McpTool(name = "get_portfolio", description = "查组合汇总(多交易所资产+USDT估值). 无账户返空 summary(totalUsdt=0).")
    public PortfolioSummaryView getPortfolio() {
        return PortfolioSummaryView.from(portfolioService.getSummary(SecurityUtils.currentUserId()));
    }

    @McpTool(
            name = "get_trade_history",
            description = "查交易历史(含盈亏/手续费统计). accountId/symbol/since/until 可省略. "
                    + "since/until 是 ISO-8601. 返回 items+stats(总成交额/手续费/已实现盈亏).")
    public TradeHistoryPageView getTradeHistory(
            @McpToolParam(description = "账户ID(可省略查全部)", required = false) Long accountId,
            @McpToolParam(description = "交易对过滤(可省略)", required = false) String symbol,
            @McpToolParam(description = "起始 ISO-8601(可省略)", required = false) String since,
            @McpToolParam(description = "结束 ISO-8601(可省略)", required = false) String until,
            @McpToolParam(description = "页码(从1, 可省略)", required = false) Integer page,
            @McpToolParam(description = "每页大小(可省略)", required = false) Integer pageSize) {
        long userId = SecurityUtils.currentUserId();
        if (accountId != null) {
            accountService.getOwned(accountId, userId);
        }
        Instant startTime = since != null ? parseParam(since, Instant::parse, "since") : null;
        Instant endTime = until != null ? parseParam(until, Instant::parse, "until") : null;
        PageQuery pq = PageQuery.ofStandard(page, pageSize);
        PageDto<TradeHistoryItem> result = tradeHistoryService.query(userId, accountId, symbol, startTime, endTime, pq);
        TradeHistoryStats stats = tradeHistoryService.stats(userId, accountId, startTime);
        return TradeHistoryPageView.from(result, stats);
    }

    private static <T> T parseParam(String raw, Function<String, T> parser, String desc) {
        try {
            return parser.apply(raw);
        } catch (RuntimeException e) {
            throw new McpToolParamInvalidException("invalid " + desc + ": " + raw);
        }
    }
}
