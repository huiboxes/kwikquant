package com.kwikquant.trading.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.PerpMath;
import com.kwikquant.shared.types.Symbol;
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

    /** key = exchange + ":" + canonical symbol(多交易所部署下同 symbol 价格不得互串),
     * value = 最新 markPrice。CROSS 强平聚合用;Caffeine 限 512/30min 防无界。 */
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

    /** 更新 symbol markPrice(PaperExecutor onTicker 调,供 CROSS 强平聚合读;按 exchange 分键)。 */
    public void updateMarkPrice(Exchange exchange, String symbol, BigDecimal markPrice) {
        if (exchange != null && symbol != null && markPrice != null) {
            markPriceCache.put(cacheKey(exchange, symbol), markPrice);
        }
    }

    private static String cacheKey(Exchange exchange, String symbol) {
        return exchange.name() + ":" + symbol;
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
        BalanceSnapshot snap = balanceService.fetchBalance(accountId, account.getUserId(), MarketType.PERP);
        // 按 quote 币种分桶逐桶判定:不同 quote 的名义额/维持保证金不可跨币相加。当前配置 USDT-only
        // = 单桶,行为与旧版逐字一致;放开多 quote(QuoteCurrencyProperties)后不再错钱误判。
        java.util.Map<String, List<Position>> byQuote = new java.util.LinkedHashMap<>();
        for (Position p : crossPositions) {
            byQuote.computeIfAbsent(Symbol.splitQuoteCurrency(p.getSymbol()), k -> new java.util.ArrayList<>())
                    .add(p);
        }
        for (var entry : byQuote.entrySet()) {
            checkQuoteBucket(accountId, account.getExchange(), snap, entry.getKey(), entry.getValue());
        }
    }

    private void checkQuoteBucket(
            long accountId, Exchange accountExchange, BalanceSnapshot snap, String quote, List<Position> bucket) {
        if (snap != null && (snap.currencies() == null || !snap.currencies().containsKey(quote))) {
            // 该 quote 无余额行:free 按 0 判(保守,同旧版 USDT 缺行语义),留痕防静默误强平
            log.warn(
                    "[paper] cross liquidation: no {} balance row for account {} (free treated as 0,"
                            + " action=manual-review)",
                    quote,
                    accountId);
        }
        BigDecimal free = extractFree(snap, quote);
        BigDecimal sumUnrealized = BigDecimal.ZERO;
        BigDecimal sumMaintMargin = BigDecimal.ZERO;
        for (Position p : bucket) {
            if (p.getQty() == null || p.getQty().signum() <= 0) {
                // 脏行(非正 qty,findCrossPerpByAccount 的 !isFlat 滤不掉负数)不参与聚合:
                // 内核对 qty≤0 抛异常会中断该账户本轮判定,旧行为是静默少算维持保证金(同样错)
                log.warn(
                        "[paper] cross liquidation: non-positive qty for {} positionId={} (skip, action=manual-review)",
                        p.getSymbol(),
                        p.getId());
                continue;
            }
            String posSide = p.getPositionSide();
            if (!PerpMath.SIDE_LONG.equals(posSide) && !PerpMath.SIDE_SHORT.equals(posSide)) {
                // 脏行守卫(与 PaperExecutor ISOLATED 分支同款):processLiquidation 按 positionSide
                // 派生四向,脏值会在触发后每 tick 抛 IllegalStateException + warn 洪泛且永不强平——
                // 判定阶段就跳过并留人工痕迹
                log.warn(
                        "[paper] cross liquidation: dirty positionSide={} for {} positionId={}"
                                + " (skip, action=manual-review)",
                        posSide,
                        p.getSymbol(),
                        p.getId());
                continue;
            }
            BigDecimal mp = markPriceCache.getIfPresent(cacheKey(accountExchange, p.getSymbol()));
            if (mp == null || mp.signum() <= 0) {
                log.warn("[paper] cross liquidation: no markPrice for {} (skip unrealizedPnl)", p.getSymbol());
                continue; // 无 markPrice 该仓 unrealizedPnl 不算(保守,不强平)
            }
            BigDecimal upl = p.getUnrealizedPnl(mp);
            if (upl != null) sumUnrealized = sumUnrealized.add(upl);
            // 单仓维持保证金不舍入,聚合后一次判定(docs/perp-math-spec.md §3.5/§3.7,双侧 fixtures 对拍)
            sumMaintMargin = sumMaintMargin.add(
                    PerpMath.maintenanceMarginRequired(mp, p.getQty(), PerpMath.DEFAULT_MAINT_MARGIN_RATE));
        }
        BigDecimal marginBalance = free.add(sumUnrealized);
        if (PerpMath.marginBreached(marginBalance, sumMaintMargin)) {
            log.info(
                    "[paper] cross liquidation triggered: accountId={} quote={} marginBalance={} maintMargin={}",
                    accountId,
                    quote,
                    marginBalance,
                    sumMaintMargin);
            for (Position p : bucket) {
                BigDecimal mp = markPriceCache.getIfPresent(cacheKey(accountExchange, p.getSymbol()));
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

    private static BigDecimal extractFree(BalanceSnapshot snap, String quote) {
        if (snap == null || snap.currencies() == null) return BigDecimal.ZERO;
        var bal = snap.currencies().get(quote);
        return bal != null && bal.free() != null ? bal.free() : BigDecimal.ZERO;
    }
}
