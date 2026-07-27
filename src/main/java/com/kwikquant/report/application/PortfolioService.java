package com.kwikquant.report.application;

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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final int SCALE = 8;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private final ExchangeAccountService accountService;
    private final BalanceService balanceService;
    private final MarketDataService marketDataService;
    private final PositionService positionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final JdbcTemplate jdbcTemplate;

    public PortfolioService(
            ExchangeAccountService accountService,
            BalanceService balanceService,
            MarketDataService marketDataService,
            PositionService positionService,
            SimpMessagingTemplate messagingTemplate,
            JdbcTemplate jdbcTemplate) {
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.marketDataService = marketDataService;
        this.positionService = positionService;
        this.messagingTemplate = messagingTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param mode "PAPER" = 仅模拟盘, "LIVE" = 仅实盘, null = 仅实盘(向后兼容)
     */
    public PortfolioSummary getSummary(long userId, String mode) {
        List<ExchangeAccountView> accounts = accountService.listByUser(userId);
        List<ExchangeAccountView> filtered = filterByMode(accounts, mode);

        List<AccountSummary> summaries = new ArrayList<>();
        int failCount = 0;

        for (ExchangeAccountView account : filtered) {
            try {
                BalanceSnapshot snapshot = balanceService.fetchBalance(account.id(), userId);
                List<CurrencyBalanceWithUsdt> enriched = new ArrayList<>();
                BigDecimal accountTotalUsdt = BigDecimal.ZERO;

                for (Map.Entry<String, BalanceSnapshot.CurrencyBalance> entry :
                        snapshot.currencies().entrySet()) {
                    String currency = entry.getKey();
                    BalanceSnapshot.CurrencyBalance bal = entry.getValue();
                    BigDecimal usdtValue = estimateUsdtValue(currency, bal.total(), account.exchange());
                    enriched.add(new CurrencyBalanceWithUsdt(currency, bal.free(), bal.used(), bal.total(), usdtValue));
                    accountTotalUsdt = accountTotalUsdt.add(usdtValue);
                }

                summaries.add(new AccountSummary(
                        account.id(), account.exchange(), account.label(), enriched, accountTotalUsdt));
            } catch (ExchangeException e) {
                log.warn("[portfolio] failed to fetch balance for account {}: {}", account.id(), e.getMessage());
                failCount++;
            }
        }

        if (failCount == filtered.size() && !filtered.isEmpty()) {
            throw new ExchangeException("all exchange accounts failed to fetch balance", true);
        }

        return new PortfolioSummary(summaries);
    }

    /**
     * @param mode "PAPER" = 仅模拟盘, "LIVE" = 仅实盘, null = 仅实盘(向后兼容)
     */
    public PortfolioPnl getPnl(long userId, String mode) {
        List<ExchangeAccountView> accounts = accountService.listByUser(userId);
        List<ExchangeAccountView> filtered = filterByMode(accounts, mode);
        List<PositionPnl> positionPnls = new ArrayList<>();
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;

        for (ExchangeAccountView account : filtered) {
            List<Position> positions = positionService.findByAccount(account.id());
            for (Position pos : positions) {
                if (pos.isFlat()) continue;

                BigDecimal currentPrice = getCurrentPrice(pos.getSymbol(), account.exchange());
                if (currentPrice == null) continue;

                BigDecimal unrealizedPnl;
                if (Position.SIDE_LONG.equals(pos.getSide())) {
                    unrealizedPnl = currentPrice
                            .subtract(pos.getAvgEntryPrice())
                            .multiply(pos.getQty())
                            .setScale(SCALE, RM);
                } else {
                    unrealizedPnl = pos.getAvgEntryPrice()
                            .subtract(currentPrice)
                            .multiply(pos.getQty())
                            .setScale(SCALE, RM);
                }

                positionPnls.add(new PositionPnl(
                        account.id(),
                        pos.getSymbol(),
                        pos.getSide(),
                        pos.getQty(),
                        pos.getAvgEntryPrice(),
                        currentPrice,
                        unrealizedPnl,
                        pos.getRealizedPnl()));
                totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl);
            }
        }

        return new PortfolioPnl(positionPnls, totalUnrealizedPnl);
    }

    public void pushUpdate(long userId) {
        try {
            PortfolioSummary summary = getSummary(userId, null);
            messagingTemplate.convertAndSend("/topic/portfolio/" + userId, summary);
        } catch (Exception e) {
            log.debug("[portfolio] push update failed for user {}: {}", userId, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${kwikquant.portfolio.push-interval-ms:30000}")
    void scheduledPush() {
        // Scheduled push is a no-op placeholder.
        // In production, this would iterate active WebSocket sessions and push updates.
        log.trace("[portfolio] scheduled push tick");
    }

    /**
     * 把任意币种折算到 USDT 估值口径(跨币种统一计价口径,行业默认;非 quote 币)。
     * USDT 直接返;非 USDT 币种用 {@code {currency}/USDT} ticker last 估值;ticker 缺失返 0。
     *
     * <p>honest:USDT-only 配置下所有余额币种都是 USDT,折算 trivial;
     * 多 quote 配置时若币种无 /USDT 对(如小币),估值返 0(被当 0)。USDT 脱钩时稳定币估值失真(理论)。
     */
    private BigDecimal estimateUsdtValue(String currency, BigDecimal amount, Exchange exchange) {
        if ("USDT".equalsIgnoreCase(currency)) {
            return amount;
        }
        String symbol = currency + "/USDT";
        Ticker ticker = marketDataService.getLatestTicker(exchange, MarketType.SPOT, symbol);
        if (ticker != null && ticker.last() != null) {
            return amount.multiply(ticker.last()).setScale(SCALE, RM);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getCurrentPrice(String symbol, Exchange exchange) {
        Ticker ticker = marketDataService.getLatestTicker(exchange, MarketType.SPOT, symbol);
        if (ticker != null && ticker.last() != null) {
            return ticker.last();
        }
        ticker = marketDataService.getLatestTicker(exchange, MarketType.PERP, symbol);
        return ticker != null ? ticker.last() : null;
    }

    // --- inner records ---

    public record PortfolioSummary(List<AccountSummary> accounts) {}

    public record AccountSummary(
            Long accountId,
            Exchange exchange,
            String label,
            List<CurrencyBalanceWithUsdt> balances,
            BigDecimal totalUsdt) {}

    public record CurrencyBalanceWithUsdt(
            String currency, BigDecimal free, BigDecimal used, BigDecimal total, BigDecimal usdtValue) {}

    public record PortfolioPnl(List<PositionPnl> positions, BigDecimal totalUnrealizedPnl) {}

    public record PositionPnl(
            Long accountId,
            String symbol,
            String side,
            BigDecimal qty,
            BigDecimal avgEntryPrice,
            BigDecimal currentPrice,
            BigDecimal unrealizedPnl,
            BigDecimal realizedPnl) {}

    public record EquitySnapshot(Instant time, BigDecimal equity) {}

    /**
     * 权益曲线:查 equity_snapshots 定时快照表返历史时间序列(R3 修复,替代原降级单点)。
     * snapshotEquity @Scheduled 每 {@code kwikquant.portfolio.snapshot-interval-ms}(默认 5min)采集
     * equity = 各账户 USDT total 之和 + 未实现 PnL,写入表。本方法按 (userId, mode, since) 查升序。
     * 无历史(新用户/定时任务未跑)兜底返当前单点(前端 EquityCurveChart 对单点显占位)。
     *
     * @param days 查询天数(查 snapshot_time &gt;= now - days)
     * @param mode "PAPER" 仅模拟盘 / "LIVE" 仅实盘 / null 向后兼容按 LIVE
     */
    public List<EquitySnapshot> getEquityCurve(long userId, int days, String mode) {
        String accountMode = mode != null ? mode.toUpperCase() : "LIVE";
        Instant since = Instant.now().minus(Duration.ofDays(days));
        try {
            List<EquitySnapshot> history = jdbcTemplate.query(
                    """
                    SELECT equity, snapshot_time
                    FROM equity_snapshots
                    WHERE user_id = ? AND account_mode = ? AND snapshot_time >= ?
                    ORDER BY snapshot_time ASC
                    """,
                    (rs, rowNum) -> new EquitySnapshot(
                            rs.getTimestamp("snapshot_time").toInstant(), rs.getBigDecimal("equity")),
                    userId,
                    accountMode,
                    since);
            if (!history.isEmpty()) {
                return history;
            }
        } catch (Exception e) {
            log.debug(
                    "[portfolio] getEquityCurve query failed, fallback to single point: userId={} error={}",
                    userId,
                    e.getMessage());
        }
        // 兜底:无历史快照(新用户/定时任务未跑/表未迁移)返当前单点(2 点同 value 画水平线)
        return currentEquitySnapshot(userId, mode, days);
    }

    /**
     * 定时采集权益快照(R3):每 {@code kwikquant.portfolio.snapshot-interval-ms}(默认 5min)遍历
     * 所有有账户的用户,算 PAPER + LIVE 的 equity 写入 equity_snapshots,供 getEquityCurve 查历史。
     * 单用户失败不阻断其他用户(try-catch per user)。
     */
    @Scheduled(fixedDelayString = "${kwikquant.portfolio.snapshot-interval-ms:300000}")
    void snapshotEquity() {
        List<Long> userIds;
        try {
            userIds = jdbcTemplate.queryForList("SELECT DISTINCT user_id FROM exchange_accounts", Long.class);
        } catch (Exception e) {
            log.warn("[portfolio] snapshotEquity list users failed: {}", e.getMessage());
            return;
        }
        for (long userId : userIds) {
            try {
                snapshotOne(userId, "PAPER");
                snapshotOne(userId, "LIVE");
            } catch (Exception e) {
                log.debug("[portfolio] snapshot userId={} failed: {}", userId, e.getMessage());
            }
        }
    }

    private void snapshotOne(long userId, String mode) {
        BigDecimal equity = currentEquity(userId, mode);
        if (equity == null) return;
        jdbcTemplate.update(
                "INSERT INTO equity_snapshots (user_id, account_mode, equity, snapshot_time) VALUES (?, ?, ?, ?)",
                userId,
                mode,
                equity,
                Instant.now());
    }

    /** 算当前 equity = 各账户 USDT total 之和 + 未实现 PnL。失败返 null。 */
    private BigDecimal currentEquity(long userId, String mode) {
        try {
            PortfolioSummary summary = getSummary(userId, mode);
            PortfolioPnl pnl = getPnl(userId, mode);
            BigDecimal accountsUsdtTotal = summary.accounts().stream()
                    .flatMap(a -> a.balances().stream())
                    .filter(b -> "USDT".equalsIgnoreCase(b.currency()))
                    .map(CurrencyBalanceWithUsdt::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return accountsUsdtTotal.add(pnl.totalUnrealizedPnl());
        } catch (Exception e) {
            return null;
        }
    }

    private List<EquitySnapshot> currentEquitySnapshot(long userId, String mode, int days) {
        BigDecimal equity = currentEquity(userId, mode);
        if (equity == null) return List.of();
        // 无历史快照兜底:返 since..now 两点同 value,前端画当前权益水平线(不显"暂无数据")。
        // 非真实历史(定时任务采集后才有),但保证用户首次打开就看到当前权益而非空状态。
        Instant now = Instant.now();
        return List.of(new EquitySnapshot(now.minus(Duration.ofDays(days)), equity), new EquitySnapshot(now, equity));
    }

    /**
     * 按 mode 过滤账户列表。
     * "PAPER" → 仅模拟盘; "LIVE" → 仅实盘; null/其他 → 仅实盘(向后兼容)。
     */
    private List<ExchangeAccountView> filterByMode(List<ExchangeAccountView> accounts, String mode) {
        if ("PAPER".equalsIgnoreCase(mode)) {
            return accounts.stream().filter(a -> a.paperTrading()).toList();
        }
        // LIVE or null → exclude paper (backward compatible)
        return accounts.stream().filter(a -> !a.paperTrading()).toList();
    }
}
