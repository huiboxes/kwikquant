package com.kwikquant.trading.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CROSS 全仓账户级强平判定器(从 PaperExecutor 抽出解 SRP)。
 *
 * <p>PaperExecutor 原承担 SPOT 撮合 + ISOLATED per-position 强平 + 活跃订单池 + CROSS 账户级聚合
 * 四职责,CROSS 逻辑自包含(聚合 marginBalance/maintMargin → 全平),与撮合/ISOLATED 强平无共享,
 * 抽出独立 checker 让 PaperExecutor 退回撮合 + ISOLATED 强平。
 *
 * <p>聚合该 account 所有 CROSS 仓(跨 symbol,PositionService.findCrossPerpByAccount):
 * <pre>
 *   marginBalance = paper_balance.free + SUM(CROSS 仓 unrealizedPnl)
 *   maintMargin = SUM(CROSS 仓 notional × 0.5%)
 *   marginBalance ≤ 0 或 maintMargin ≥ marginBalance → 全平所有 CROSS 仓
 * </pre>
 * 每仓 markPrice 从 markPriceCache 取(PaperExecutor onTicker 调 updateMarkPrice 更新);
 * 无缓存该仓 unrealizedPnl 不算(保守,不强平)。幂等:CAS 冲突/事务回滚下 tick 再判。
 *
 * <p>markPriceCache Caffeine 限 512 symbol + 30min 过期,防 SPOT/ISOLATED-only 场景只写不读致无界增长。
 */
@Component
public class CrossLiquidationChecker {

    private static final Logger log = LoggerFactory.getLogger(CrossLiquidationChecker.class);

    /** key = canonical symbol, value = 最新 markPrice。CROSS 强平聚合用;Caffeine 限 512/30min 防无界。 */
    private final Cache<String, BigDecimal> markPriceCache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    private final PositionService positionService;
    private final ExchangeAccountService accountService;
    private final BalanceService balanceService;
    private final ExecutionService executionService;

    public CrossLiquidationChecker(
            PositionService positionService,
            ExchangeAccountService accountService,
            BalanceService balanceService,
            ExecutionService executionService) {
        this.positionService = positionService;
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.executionService = executionService;
    }

    /** 更新 symbol markPrice(PaperExecutor onTicker 调,供 CROSS 强平聚合读)。 */
    public void updateMarkPrice(String symbol, BigDecimal markPrice) {
        if (symbol != null && markPrice != null) {
            markPriceCache.put(symbol, markPrice);
        }
    }

    /** CROSS 账户级强平判定(PaperExecutor.checkLiquidation 收集 crossAccounts 去重后调)。 */
    public void checkAccount(long accountId) {
        List<Position> crossPositions = positionService.findCrossPerpByAccount(accountId);
        if (crossPositions.isEmpty()) return;
        ExchangeAccount account = accountService.findById(accountId);
        if (account == null) {
            log.warn("[paper] cross liquidation: account {} not found", accountId);
            return;
        }
        BigDecimal free = extractUsdtFree(balanceService.fetchBalance(accountId, account.getUserId(), MarketType.PERP));
        BigDecimal sumUnrealized = BigDecimal.ZERO;
        BigDecimal sumMaintMargin = BigDecimal.ZERO;
        for (Position p : crossPositions) {
            BigDecimal mp = markPriceCache.getIfPresent(p.getSymbol());
            if (mp == null || mp.signum() <= 0) {
                log.warn("[paper] cross liquidation: no markPrice for {} (skip unrealizedPnl)", p.getSymbol());
                continue; // 无 markPrice 该仓 unrealizedPnl 不算(保守,不强平)
            }
            BigDecimal upl = p.getUnrealizedPnl(mp);
            if (upl != null) sumUnrealized = sumUnrealized.add(upl);
            BigDecimal notional = mp.multiply(p.getQty());
            sumMaintMargin = sumMaintMargin.add(notional.multiply(Position.DEFAULT_MAINT_MARGIN_RATE));
        }
        BigDecimal marginBalance = free.add(sumUnrealized);
        if (marginBalance.signum() <= 0 || sumMaintMargin.compareTo(marginBalance) >= 0) {
            log.info(
                    "[paper] cross liquidation triggered: accountId={} marginBalance={} maintMargin={}",
                    accountId,
                    marginBalance,
                    sumMaintMargin);
            for (Position p : crossPositions) {
                BigDecimal mp = markPriceCache.getIfPresent(p.getSymbol());
                if (mp == null || mp.signum() <= 0) {
                    // 无 markPrice 不能强平——用 0 会按 price=0 算 realizedPnlDelta=(0-avgEntry)×qty 致账户被错误抽干。
                    // 跳过该仓,下个 tick 缓存命中后重判(marginBalance 判定阶段已 conservative 跳过无价仓)。
                    log.warn("[paper] cross liquidation: no markPrice for {} (skip this position)", p.getSymbol());
                    continue;
                }
                try {
                    executionService.processLiquidation(p.getId(), mp, null);
                } catch (RuntimeException e) {
                    log.warn(
                            "[paper] cross liquidation failed (will retry next tick): positionId={} error={}",
                            p.getId(),
                            e.getMessage());
                }
            }
        }
    }

    private static BigDecimal extractUsdtFree(BalanceSnapshot snap) {
        if (snap == null || snap.currencies() == null) return BigDecimal.ZERO;
        var usdt = snap.currencies().get("USDT");
        return usdt != null && usdt.free() != null ? usdt.free() : BigDecimal.ZERO;
    }
}
