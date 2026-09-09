package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRatePeriod;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.PerpMath;
import com.kwikquant.trading.domain.FundingRateKind;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PAPER 资金费期次结算调度器。fixedDelay 扫描 PAPER PERP 持仓,按 funding_rates 期序列
 * (交易所资金费网格,8h/4h/1h 由数据决定)逐期结算,调 processFundingSettlement 事务内
 * 扣/加 paper_balance + 落账 funding_settlements(期次幂等键)。
 *
 * <p><b>期次语义(V59)</b>:每条结算锚定 funding_time(交易所结算时刻),费率取该期
 * <b>已结算值</b>(funding_rates.settled_rate)。不再用 CCXT 实时预估值——预估值指向下一个
 * 未结算期次,与已结算值可差数倍且相邻期符号会翻转,拿它结算已结束的期次连方向都可能算反。
 *
 * <p><b>catch-up</b>:每仓结算下界 floor = max(上期结算 watermark, openedAt),(floor, now)
 * 内的已结算期次按 ASC <b>严格顺序</b>逐期结算——宕机/重启漏掉的期次会被逐期补上(paper
 * 持仓只在应用运行时变化,宕机期间仓位冻结,用当前 qty 补结历史期次是精确的);openedAt
 * 防开仓前的历史期次被错误回收(flat 期不收)。遇到第一个不可结期次(标记价缺失等)即停止
 * 等下一轮——静默跳期会漏钱。watermark 与 openedAt 双 null 的存量仓(V59 前开仓且从未
 * 结算过)fallback 只回看 {@value #FALLBACK_WINDOW_SECONDS}s(补最近期次,不把回填的
 * 94 天历史全收一遍)。
 *
 * <p><b>已知近似</b>:期次到期与仓位全平落在同一 pass 间隔内时,!isFlat 过滤使该期不补收
 * (窗口 ≤1 pass/期,方向对用户有利);每仓每轮限额 {@code max-periods-per-pass}(默认 3)防
 * 数据迟到场景一次补结数百期(金额按当前 qty 口径失真 + 事件风暴),剩余期次 ASC 续接下轮。
 *
 * <p><b>标记价近似</b>:优先 funding_rates 行的 mark_price(OKX unified 无标记价,实际恒
 * null);缺失用当前 ticker mid((bid+ask)/2,fallback last)近似——费率是每期精确值、
 * 标记价是近似值,不声称与交易所账单逐位等价。
 *
 * <p>符号约定(OKX 语义):正费率多头付空头收,负费率反。金额计算单源在
 * {@link PerpMath#fundingAmount}(docs/perp-math-spec.md §3.8),已带符号(正=收加余额,
 * 负=付扣余额)。positionSide 非 LONG/SHORT(null/脏数据)内核抛 INVALID_SIDE → 跳过该仓
 * 并 warn(fail-closed,不猜方向)。结算币种从 symbol quote 段派生,非法 symbol 跳过。
 */
@Component
public class PaperFundingSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaperFundingSettlementScheduler.class);

    /** 存量仓(watermark/openedAt 双 null)的 fallback 回看窗口:8h,至多补最近一期。 */
    static final long FALLBACK_WINDOW_SECONDS = 28800L;

    private final ExchangeAccountService accountService;
    private final PositionService positionService;
    private final MarketDataService marketDataService;
    private final FundingSettlementService fundingSettlementService;

    /** 空序列 warn 限频(positionId → 上次告警时刻):每分钟 pass 不刷屏,6h 一次留观测痕迹。 */
    private final java.util.Map<Long, Instant> emptySeriesWarnedAt = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long EMPTY_SERIES_WARN_INTERVAL_SECONDS = 21600;

    /** interval 缺失行的同期次判定下限容差(58min):网格 ≥1h 假设下,合法负抖动真期次差分
     * ≥3540s(FundingSeries 抖动模型),双行漂移是分钟级——3480 与 3540 之间留 60s 隔离带,
     * 容差闭区间(≤3480 跳)不与合法抖动下界重叠。见 settlePosition 内注释。 */
    static final long SAME_PERIOD_FLOOR_TOLERANCE_SECONDS = 3480;

    /**
     * 每仓每轮最多结算期次数:数据迟到场景(采集配置补开/回填落地/代理补洞)一次 pass 可能拿到
     * 数百期,逐期独立事务+逐期 WS 事件 = 金额口径风险叠加事件风暴。限额后按 ASC 续接下轮
     * (每分钟一轮,数百期一小时内追平),漏期语义不变(严格顺序+watermark)。
     * 配置 ≤0 = 不限额(整段一次结完,单测依赖该语义注入 0)。
     */
    @org.springframework.beans.factory.annotation.Value("${kwikquant.funding.settlement.max-periods-per-pass:3}")
    int maxPeriodsPerPass;

    public PaperFundingSettlementScheduler(
            ExchangeAccountService accountService,
            PositionService positionService,
            MarketDataService marketDataService,
            FundingSettlementService fundingSettlementService) {
        this.accountService = accountService;
        this.positionService = positionService;
        this.marketDataService = marketDataService;
        this.fundingSettlementService = fundingSettlementService;
    }

    /**
     * 期次结算入口,fixedDelay 每分钟(可配)。期次网格由 funding_rates 数据决定,调度频率
     * 只决定"新结算的期次至多延迟多久被观察到"——8h/4h/1h 合约同一循环覆盖,不再按 8h
     * cron 硬编码(旧 cron 对 4h/1h 合约必错)。
     */
    @Scheduled(
            fixedDelayString = "${kwikquant.funding.settlement.interval-ms:60000}",
            initialDelayString = "${kwikquant.funding.settlement.initial-delay-ms:30000}")
    public void settleAll() {
        Instant now = Instant.now();
        List<ExchangeAccount> paperAccounts = accountService.findAll().stream()
                .filter(ExchangeAccount::isPaperTrading)
                .toList();
        if (paperAccounts.isEmpty()) return;
        log.debug("[paper-funding] period settlement pass start: accounts={}", paperAccounts.size());
        for (ExchangeAccount account : paperAccounts) {
            try {
                settleAccount(account, now);
            } catch (RuntimeException e) {
                log.warn("[paper-funding] account {} settlement failed: {}", account.getId(), e.getMessage());
            }
        }
    }

    /**
     * 结算单账户所有 PERP 持仓(CROSS + ISOLATED,跨 symbol)。非 flat 仓才结算。
     * 单仓异常只 warn 不阻断其他仓。
     */
    void settleAccount(ExchangeAccount account, Instant now) {
        long accountId = account.getId();
        List<Position> positions = positionService.findByAccount(accountId).stream()
                .filter(p -> p.getMarginMode() != null) // PERP(ISOLATED/CROSS);SPOT marginMode=null 跳过
                .filter(p -> !p.isFlat())
                .toList();
        if (positions.isEmpty()) return;
        for (Position p : positions) {
            try {
                settlePosition(account, p, now);
            } catch (RuntimeException e) {
                log.warn("[paper-funding] position {} settle failed: {}", p.getId(), e.getMessage());
            }
        }
    }

    /**
     * 结算单仓的到期期次:(floor, now) 内已结算期次 ASC 严格顺序逐期结算。
     * floor = max(watermark, openedAt),双 null fallback now−8h。
     */
    void settlePosition(ExchangeAccount account, Position p, Instant now) {
        String currency = quoteCurrency(p.getSymbol());
        if (currency == null) {
            log.warn(
                    "[paper-funding] cannot derive quote currency from symbol {} (skip): accountId={}",
                    p.getSymbol(),
                    account.getId());
            return;
        }
        Instant watermark = fundingSettlementService.findLastFundingTime(account.getId(), p.getId());
        Instant floor = laterOf(watermark, p.getOpenedAt());
        if (floor == null) {
            // 存量仓(V59 前开仓且从未结算):只回看 8h 补最近期次,不回收全部回填历史
            floor = now.minusSeconds(FALLBACK_WINDOW_SECONDS);
        }
        List<FundingRatePeriod> periods =
                marketDataService.findSettledFundingPeriods(account.getExchange(), p.getSymbol(), floor, now);
        if (periods.isEmpty()) {
            // 该仓从未结出任何一期:symbol/exchange 大概率不在 kwikquant.funding 采集配置内,
            // 资金费永不结算 = 模拟盘收益系统性失真。限频 warn 留观测痕迹(每分钟 pass 不刷屏)。
            warnEmptySeriesThrottled(account, p);
            return;
        }

        BigDecimal tickerMid = null;
        boolean tickerFetched = false;
        int settledThisPass = 0;
        // 防重结游标:每结算一期即推进(初值=watermark)。跨 pass"换代表"(先按 PROXY 精确网格行
        // 结过,回填后读侧改推原生漂移行)与同 pass 双行(窗口同时含网格行+漂移行)都靠它;
        // 不依赖读侧 dedupe——每分钟 pass 的窗口常只有 0~1 个新期次,行数不足派生间隔,去重原样放行
        Instant lastSettled = watermark;
        for (FundingRatePeriod period : periods) {
            // [floor, now) 查询含边界行:funding_time ≤ floor 的期次已结算(watermark)或早于开仓,跳过
            if (period.fundingTime().compareTo(floor) <= 0) continue;
            // 同期次双行防二次扣钱,容差两档:
            // - interval 有声明/富化 → max(60s, interval/4)(与读侧去重同口径);
            // - interval null(漂移行常态:富化要求局部差分 ≥1h,双行相邻差分是分钟级,恒富化不出)
            //   → 下限容差 58min(3480s):交易所期次网格 ≥1h,合法负抖动真期次差分 ≥3540s
            //   (FundingSeries 抖动模型),双行漂移是分钟级——3480 与 3540 间留隔离带,
            //   容差闭区间不与合法抖动下界重叠(旧值 3540 含等号会与抖动模型自相矛盾)
            if (lastSettled != null) {
                long tolSecs = period.intervalSeconds() != null && period.intervalSeconds() > 0
                        ? Math.max(60L, period.intervalSeconds() / 4L)
                        : SAME_PERIOD_FLOOR_TOLERANCE_SECONDS;
                if (!period.fundingTime().isAfter(lastSettled.plusSeconds(tolSecs))) {
                    log.info(
                            "[paper-funding] skip same-period re-representation: accountId={} positionId={}"
                                    + " fundingTime={} lastSettled={} (within {}s tolerance)",
                            account.getId(),
                            p.getId(),
                            period.fundingTime(),
                            lastSettled,
                            tolSecs);
                    continue;
                }
            }
            BigDecimal markPrice = period.markPrice();
            if (markPrice == null || markPrice.signum() <= 0) {
                if (!tickerFetched) {
                    tickerMid = currentTickerMid(account, p.getSymbol());
                    tickerFetched = true;
                }
                markPrice = tickerMid;
            }
            if (markPrice == null || markPrice.signum() <= 0) {
                // 严格顺序:第一个不可结期次即停止(跳期会静默漏钱),标记价恢复后下一轮从本期续结
                log.warn(
                        "[paper-funding] no markPrice for period {} of {} (stop, retry next pass): accountId={}",
                        period.fundingTime(),
                        p.getSymbol(),
                        account.getId());
                return;
            }
            BigDecimal rate = period.settledRate();
            // 符号约定/舍入单源在内核(docs/perp-math-spec.md §3.8,双侧 fixtures 对拍):
            // 正费率多头付(扣余额)空头收(加余额),用 PERP 规范字段 positionSide("LONG"/"SHORT")。
            // positionSide 非 LONG/SHORT(null/脏数据)内核抛 INVALID_SIDE → settleAccount 捕获后
            // 跳过该仓并 warn——fail-closed,不默认按 LONG 猜方向(猜错即静默金融方向翻转)。
            BigDecimal fundingAmount = PerpMath.fundingAmount(p.getPositionSide(), rate, markPrice, p.getQty());

            // 余额扣减在 processFundingSettlement 事务内(期次幂等键,重跑/并发撞键早返不双扣;
            // ISOLATED 侵蚀仓位保证金 / CROSS 入账户现金由 service 按 marginMode 分流)
            if ("PROXY_BINANCE".equals(period.source())) {
                // 跨所代理行入账:audit metadata 记 source(经 FundingSettleCommand.rateSource),
                // 这里再留一条 warn——paper 账户资金费口径被代理值改写必须可观测
                log.warn(
                        "[paper-funding] settling period from PROXY_BINANCE rate: accountId={} positionId={}"
                                + " symbol={} fundingTime={} (cross-exchange proxy, basis risk)",
                        account.getId(),
                        p.getId(),
                        p.getSymbol(),
                        period.fundingTime());
            }
            fundingSettlementService.processFundingSettlement(new FundingSettleCommand(
                    account.getId(),
                    p.getId(),
                    p.getSymbol(),
                    rate,
                    p.getQty(),
                    fundingAmount,
                    period.fundingTime(),
                    FundingRateKind.SETTLED,
                    period.intervalSeconds(),
                    markPrice,
                    currency,
                    p.getMarginMode(),
                    period.source()));
            log.info(
                    "[paper-funding] settled period: accountId={} positionId={} symbol={} fundingTime={}"
                            + " rate={} amount={}",
                    account.getId(),
                    p.getId(),
                    p.getSymbol(),
                    period.fundingTime(),
                    rate,
                    fundingAmount);
            lastSettled = period.fundingTime();
            settledThisPass++;
            if (maxPeriodsPerPass > 0 && settledThisPass >= maxPeriodsPerPass) {
                // 限额续接:剩余期次下一轮 pass 从新 watermark 接着结(ASC 严格顺序,不漏不跳)
                log.info(
                        "[paper-funding] per-pass cap {} reached, remaining periods continue next pass:"
                                + " accountId={} positionId={} symbol={}",
                        maxPeriodsPerPass,
                        account.getId(),
                        p.getId(),
                        p.getSymbol());
                break;
            }
        }
    }

    /** 空序列限频 warn:同一仓 6h 内只报一次(每分钟 pass,不限频会刷屏淹没真告警)。 */
    private void warnEmptySeriesThrottled(ExchangeAccount account, Position p) {
        Instant now = Instant.now();
        Instant last = emptySeriesWarnedAt.get(p.getId());
        if (last != null && last.plusSeconds(EMPTY_SERIES_WARN_INTERVAL_SECONDS).isAfter(now)) {
            return;
        }
        // 顺手清过期 entry:map 以 positionId 为键只增不减,长驻进程防慢泄漏(已销毁仓位的痕迹)
        emptySeriesWarnedAt.entrySet().removeIf(e -> e.getValue()
                .plusSeconds(EMPTY_SERIES_WARN_INTERVAL_SECONDS * 2)
                .isBefore(now));
        emptySeriesWarnedAt.put(p.getId(), now);
        log.warn(
                "[paper-funding] no settled funding periods for {} {} since watermark/openedAt"
                        + " — symbol/exchange 可能不在 kwikquant.funding 采集配置内,该仓资金费不会被结算"
                        + " (模拟盘收益失真, action=check-funding-config): accountId={}",
                account.getExchange(),
                p.getSymbol(),
                account.getId());
    }

    /** 当前 ticker mid((bid+ask)/2,fallback last,复用 {@link PaperExecutor#computeMarkPrice});不可得返 null。 */
    private BigDecimal currentTickerMid(ExchangeAccount account, String symbol) {
        try {
            Ticker t = marketDataService.getLatestTicker(account.getExchange(), MarketType.PERP, symbol);
            return t != null ? PaperExecutor.computeMarkPrice(t) : null;
        } catch (RuntimeException e) {
            log.warn("[paper-funding] ticker mid unavailable for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** 两时刻取晚者;任一为 null 返另一个;双 null 返 null。 */
    static Instant laterOf(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /**
     * 从 CCXT 规范 symbol 派生 quote 币种:BTC/USDT → USDT;BTC/USDT:USDT(PERP 线性结算后缀)→ USDT。
     * 无 '/' 或 quote 段为空返 null(调用方 fail-closed 跳过,不按错币种落账)。
     */
    static String quoteCurrency(String symbol) {
        if (symbol == null) return null;
        int slash = symbol.indexOf('/');
        if (slash < 0 || slash == symbol.length() - 1) return null;
        String quote = symbol.substring(slash + 1);
        int colon = quote.indexOf(':');
        if (colon >= 0) quote = quote.substring(0, colon);
        return quote.isBlank() ? null : quote;
    }
}
