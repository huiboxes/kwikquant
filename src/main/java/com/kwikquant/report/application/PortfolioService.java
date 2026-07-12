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

    public PortfolioSummary getSummary(long userId) {
        List<ExchangeAccountView> accounts = accountService.listByUser(userId);
        // 模拟盘账户不计入组合总额（避免模拟资金和真实资金混算误导使用者）。
        List<ExchangeAccountView> nonPaper =
                accounts.stream().filter(a -> !a.paperTrading()).toList();

        List<AccountSummary> summaries = new ArrayList<>();
        int failCount = 0;

        for (ExchangeAccountView account : nonPaper) {
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

        if (failCount == nonPaper.size() && !nonPaper.isEmpty()) {
            throw new ExchangeException("all exchange accounts failed to fetch balance", true);
        }

        BigDecimal totalUsdt =
                summaries.stream().map(AccountSummary::totalUsdt).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PortfolioSummary(summaries, totalUsdt);
    }

    public PortfolioPnl getPnl(long userId) {
        List<ExchangeAccountView> accounts = accountService.listByUser(userId);
        // 同 getSummary：模拟盘不计入未实现盈亏总额，理由一致（避免模拟/真实资金混算）。
        List<ExchangeAccountView> nonPaper =
                accounts.stream().filter(a -> !a.paperTrading()).toList();
        List<PositionPnl> positionPnls = new ArrayList<>();
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;

        for (ExchangeAccountView account : nonPaper) {
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
            PortfolioSummary summary = getSummary(userId);
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
}
