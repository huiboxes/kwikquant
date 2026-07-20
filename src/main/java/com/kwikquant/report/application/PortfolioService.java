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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public PortfolioService(
            ExchangeAccountService accountService,
            BalanceService balanceService,
            MarketDataService marketDataService,
            PositionService positionService,
            SimpMessagingTemplate messagingTemplate) {
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.marketDataService = marketDataService;
        this.positionService = positionService;
        this.messagingTemplate = messagingTemplate;
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

        BigDecimal totalUsdt =
                summaries.stream().map(AccountSummary::totalUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PortfolioSummary(summaries, totalUsdt);
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

    public record PortfolioSummary(List<AccountSummary> accounts, BigDecimal totalUsdt) {}

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
     * 降级版权益曲线：返回当前时刻的单点快照（totalUsdt + totalUnrealizedPnl）。后续版本将补充定时采集的完整时间序列。
     *
     * @param days 暂未使用，预留给后续基于历史快照的查询
     */
    @SuppressWarnings("unused")
    public List<EquitySnapshot> getEquityCurve(long userId, int days, String mode) {
        try {
            PortfolioSummary summary = getSummary(userId, mode);
            PortfolioPnl pnl = getPnl(userId, mode);
            BigDecimal equity = summary.totalUsdt().add(pnl.totalUnrealizedPnl());
            return List.of(new EquitySnapshot(Instant.now(), equity));
        } catch (Exception e) {
            return List.of();
        }
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
