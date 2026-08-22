package com.kwikquant.report.application;

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
import com.kwikquant.trading.application.PositionEnricher;
import com.kwikquant.trading.application.PositionEnrichment;
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
    private final PositionEnricher positionEnricher;
    private final SimpMessagingTemplate messagingTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final PortfolioSubscriptionRegistry portfolioSubscriptionRegistry;

    public PortfolioService(
            ExchangeAccountService accountService,
            BalanceService balanceService,
            MarketDataService marketDataService,
            PositionService positionService,
            PositionEnricher positionEnricher,
            SimpMessagingTemplate messagingTemplate,
            JdbcTemplate jdbcTemplate,
            PortfolioSubscriptionRegistry portfolioSubscriptionRegistry) {
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.marketDataService = marketDataService;
        this.positionService = positionService;
        this.positionEnricher = positionEnricher;
        this.messagingTemplate = messagingTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.portfolioSubscriptionRegistry = portfolioSubscriptionRegistry;
    }

    /**
     * @param mode "LIVE" = 仅实盘, 其他/null = 仅模拟盘（默认 PAPER 口径）
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
                        account.id(),
                        account.exchange(),
                        account.paperTrading(),
                        account.label(),
                        enriched,
                        accountTotalUsdt));
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
     * 跨账户持仓聚合。与交易页口径一致:只要持仓存在(非 flat)就返回一行,行情缺失不吞行——
     * currentPrice/unrealizedPnl 为 null,前端渲染"—",避免行情不可用时组合页静默空仓而交易页有仓的口径分裂。
     * totalUnrealizedPnl 只累加可估值的行(行情缺失不计入,而非当 0)。
     *
     * @param mode "LIVE" = 仅实盘, 其他/null = 仅模拟盘（默认 PAPER 口径）
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

                PositionEnrichment enrichment = positionEnricher.enrich(pos, account.exchange());
                BigDecimal currentPrice = enrichment.currentPrice();
                BigDecimal unrealizedPnl = enrichment.unrealizedPnl();
                if (unrealizedPnl != null) {
                    unrealizedPnl = unrealizedPnl.setScale(SCALE, RM);
                    totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl);
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
        // 遍历活跃 portfolio 订阅者推送快照。activeUserIds() 已去重(同 userId 多 session 单 entry),
        // pushUpdate 一次 convertAndSend 到 /topic/portfolio/{userId}(broadcast),该 userId 所有 session 同收,
        // 故同一 tick 同一 userId 只拉一次远端余额——不存在"多订阅者多次拉"的重复。
        // pushUpdate 内部已 catch,单用户失败不阻断循环。
        //
        // 决策(不引入缓存/限频):
        // - 不缓存 summary:余额因下单/成交实时变,缓存 stale 会误导用户;失效需成交事件驱动,属于较大
        //   工程,暂不做。REST 刷新与 scheduledPush 短窗口偶发重复拉是用户主动行为,可接受。
        // - 不按 userId 限频:当前用户量小,30s fixedDelay 温和,不撞交易所限频;用户量增长撞限频时再加。
        //
        // 多实例:scheduledPush 每实例独立跑,推本实例 registry 的 userId 到本实例 simple broker,
        // session 与 broker 同实例即正确工作;跨实例推送需共享 broker + 共享 registry,当前无该场景。
        // 见 PortfolioSubscriptionRegistry 契约 javadoc。
        for (long userId : portfolioSubscriptionRegistry.activeUserIds()) {
            pushUpdate(userId);
        }
    }

    /**
     * 把任意币种折算到 USDT 估值口径(跨币种统一计价口径,行业默认;非 quote 币)。
     * USDT 直接返;非 USDT 币种用 {@code {currency}/USDT} ticker last 估值;ticker 缺失返 0。
     *
     * <p>注:USDT-only 配置下所有余额币种都是 USDT,折算 trivial;
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

    // --- inner records ---

    public record PortfolioSummary(List<AccountSummary> accounts) {}

    /**
     * 单账户摘要。模拟/实盘的判定依据是 {@code paperTrading}(建号禁止 exchange=PAPER,DB CHECK 约束),
     * exchange 仅表示接入的交易所——前端不得用 exchange 枚举区分模拟/实盘。
     */
    public record AccountSummary(
            Long accountId,
            Exchange exchange,
            boolean paperTrading,
            String label,
            List<CurrencyBalanceWithUsdt> balances,
            BigDecimal totalUsdt) {}

    public record CurrencyBalanceWithUsdt(
            String currency, BigDecimal free, BigDecimal used, BigDecimal total, BigDecimal usdtValue) {}

    public record PortfolioPnl(List<PositionPnl> positions, BigDecimal totalUnrealizedPnl) {}

    /** 行情缺失时 currentPrice/unrealizedPnl 为 null(前端显"—"),avgEntryPrice/realizedPnl 亦可能为 null。 */
    public record PositionPnl(
            Long accountId,
            String symbol,
            String side,
            BigDecimal qty,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) BigDecimal avgEntryPrice,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "行情缺失时为 null")
                    BigDecimal currentPrice,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true, description = "行情缺失时为 null")
                    BigDecimal unrealizedPnl,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) BigDecimal realizedPnl) {}

    public record EquitySnapshot(Instant time, BigDecimal equity) {}

    /**
     * 权益曲线:查 equity_snapshots 定时快照表返历史时间序列。
     * snapshotEquity @Scheduled 每 {@code kwikquant.portfolio.snapshot-interval-ms}(默认 5min)采集
     * equity = 各账户 USDT total 之和 + 未实现 PnL,写入表。本方法按 (userId, mode, since) 查升序。
     * 无历史(新用户/定时任务未跑)兜底返当前单点(前端 EquityCurveChart 对单点显占位)。
     *
     * @param days 查询天数(查 snapshot_time &gt;= now - days)
     * @param mode "LIVE" 仅实盘 / 其他/null 仅模拟盘（默认 PAPER 口径）
     */
    public List<EquitySnapshot> getEquityCurve(long userId, int days, String mode) {
        // null 默认 PAPER,与 filterByMode 口径一致。
        String accountMode = mode != null ? mode.toUpperCase() : "PAPER";
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
     * 定时采集权益快照:每 {@code kwikquant.portfolio.snapshot-interval-ms}(默认 5min)遍历
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
     * "LIVE" → 仅实盘; 其他/null → 仅模拟盘（全系统默认 PAPER 口径, Controller 层 defaultValue 同）。
     */
    private List<ExchangeAccountView> filterByMode(List<ExchangeAccountView> accounts, String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return accounts.stream().filter(a -> !a.paperTrading()).toList();
        }
        return accounts.stream().filter(a -> a.paperTrading()).toList();
    }
}
