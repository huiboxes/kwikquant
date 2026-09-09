package com.kwikquant.market.application;

import com.kwikquant.market.domain.FundingRatePeriod;
import com.kwikquant.market.infrastructure.FundingRateMapper;
import com.kwikquant.shared.types.Exchange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PERP 回测资金费序列覆盖度预检(docs/perp-backtest-spec.md §7,fail-closed)。
 *
 * <p>按任务区间生成期望期次网格(epoch 对齐,interval 取自序列行,不硬编码周期),对照
 * {@code funding_rates} 已结算行;缺期即拒,错误信息列出缺失概况与两个出路(缩短区间 /
 * 显式 {@code allowFundingProxy})。降级语义:<b>预检 fail-closed,运行期零降级</b>——零费率
 * 跳过 = 静默错钱,运行期 fail = 用户等完整个回测才失败,预检是唯一不浪费 worker 的卡点。
 *
 * <p>匹配容差 {@code interval/4}(最小 60s):交易所期次时刻存在历史漂移(如结算延迟分钟级),
 * 精确网格相等会误报缺期。近端宽限 1 个 interval:最新期次可能尚未到结算时刻,不算缺。
 *
 * <p>跨所代理(显式 opt-in):{@code allowProxy=true} 且本所缺期时,用 BINANCE 同期次已结算值
 * 写入本所行({@code source=PROXY_BINANCE},跨所基差风险由报告 warnings 显性标注);默认
 * false = fail-closed,绝不静默代理。BINANCE 任务自身缺期无代理源,直接拒。
 */
@Service
public class FundingCoverageGuard {

    private static final Logger log = LoggerFactory.getLogger(FundingCoverageGuard.class);

    /** 期次匹配容差下限(秒):1h 合约 interval/4=15min 已够宽,更短周期不低于 60s。 */
    private static final long MIN_TOLERANCE_SECONDS = 60;

    private final MarketDataService marketDataService;
    private final FundingRateMapper fundingRateMapper;

    public FundingCoverageGuard(MarketDataService marketDataService, FundingRateMapper fundingRateMapper) {
        this.marketDataService = marketDataService;
        this.fundingRateMapper = fundingRateMapper;
    }

    /**
     * 覆盖度检查结果。
     *
     * @param settledPeriods 区间内已结算期次数(代理补写后口径)
     * @param proxyApplied   本次以 PROXY_BINANCE 补写的期次数(0 = 未用代理)
     */
    public record CoverageResult(int settledPeriods, int proxyApplied) {}

    /**
     * 校验 {@code [start, end)} 的已结算资金费序列完整性;缺期抛 {@link IllegalArgumentException}
     * (REST 入口转 400/3001,Gateway 执行前复查转 markFailed)。
     *
     * @param allowProxy 显式允许 Binance 跨所代理补写缺失期次(写库,幂等 upsert)
     * @param now        当前时刻(近端宽限判定;调用方传入,测试可注入固定值)
     */
    public CoverageResult ensureCoverage(
            Exchange exchange, String symbol, Instant start, Instant end, boolean allowProxy, Instant now) {
        List<FundingRatePeriod> settled = marketDataService.findSettledFundingPeriods(exchange, symbol, start, end);
        long interval = resolveInterval(exchange, symbol, settled);
        if (!allowProxy) {
            // 此前某次 opt-in 任务写入的 PROXY_BINANCE 行会被本次静默消费——留观测痕迹
            // (用户侧披露由 worker 报告 warnings 承担,此处只补服务端日志)
            long proxyRows = settled.stream()
                    .filter(p -> "PROXY_BINANCE".equals(p.source()))
                    .count();
            if (proxyRows > 0) {
                log.warn(
                        "[funding-coverage] {}.{}: consuming {} pre-existing PROXY_BINANCE period(s) in [{}, {})"
                                + " without allowFundingProxy (written by an earlier opt-in task)",
                        exchange,
                        symbol,
                        proxyRows,
                        start,
                        end);
            }
        }
        List<Instant> missing = findMissing(settled, start, end, interval, now);
        int proxyApplied = 0;
        if (!missing.isEmpty() && allowProxy && exchange != Exchange.BINANCE) {
            proxyApplied = proxyFillFromBinance(exchange, symbol, missing, interval);
            if (proxyApplied > 0) {
                log.info(
                        "[funding-coverage] {}.{}: applied {} PROXY_BINANCE periods for [{}, {})",
                        exchange,
                        symbol,
                        proxyApplied,
                        start,
                        end);
                // 代理补写后重查重检(仍缺 = Binance 也缺该期,无代理源)
                settled = marketDataService.findSettledFundingPeriods(exchange, symbol, start, end);
                missing = findMissing(settled, start, end, interval, now);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(buildMissingMessage(exchange, symbol, start, end, missing, allowProxy));
        }
        return new CoverageResult(settled.size(), proxyApplied);
    }

    /**
     * interval 从序列行读取(1h/4h/8h 通吃,不硬编码);声明缺失时按相邻期次差分派生
     * (历史/回填行 interval_seconds 恒 null——persistSettled 不带 interval,纯历史区间
     * 只有回填行,不派生则长区间回测被系统性误拒);派生不出(行数 &lt;3 或无重复差分簇)
     * 才 fail-closed 拒。
     */
    private long resolveInterval(Exchange exchange, String symbol, List<FundingRatePeriod> settled) {
        if (settled.isEmpty()) {
            throw new IllegalArgumentException(
                    "PERP 回测资金费数据缺失：" + exchange + " " + symbol + " 无任何已结算资金费序列(部署后需等待采集/回填完成,或缩短回测区间至有数据的窗口)");
        }
        for (FundingRatePeriod p : settled) {
            if (p.intervalSeconds() != null && p.intervalSeconds() > 0) {
                return p.intervalSeconds();
            }
        }
        Long derived = FundingSeries.deriveIntervalSeconds(
                settled.stream().map(FundingRatePeriod::fundingTime).toList());
        if (derived != null) {
            return derived;
        }
        throw new IllegalArgumentException("PERP 回测资金费数据异常：" + exchange + " " + symbol
                + " 序列行缺少 intervalSeconds 声明且无法从相邻期次差分派生,无法生成期望期次网格(fail-closed)");
    }

    /**
     * 缺期检测(分段网格):头段 start→首行按 epoch 对齐网格;中段逐相邻行 gap 按该段 interval
     * 判定(取两行声明的较大者,与 worker {@code find_funding_gaps} 的 max-interval 判据同口径,
     * 历史 4h→8h 网格切换不再被单一全局 interval 误拒/漏检);尾段末行→end 步进。容差
     * max(60s, interval/4) 吸收期次时刻漂移;近端宽限 1 fallback interval(最新期次可能尚未结算)。
     * 缺期点从中段前行锚定步进生成(供代理补写按容差窗反查 Binance)。
     */
    private static List<Instant> findMissing(
            List<FundingRatePeriod> settled, Instant start, Instant end, long fallbackInterval, Instant now) {
        long graceBefore = now.minusSeconds(fallbackInterval).getEpochSecond();
        long endEpoch = end.getEpochSecond();
        List<Instant> missing = new ArrayList<>();
        FundingRatePeriod first = settled.get(0);
        long headIv = intervalOf(first, fallbackInterval);
        // 头段锚定首行向后步进(不用 epoch 对齐网格):派生/富化 interval 带抖动(如 3540s)时,
        // epoch 网格与真实行位错开可达整个 interval(系统性漂移下错位均匀分布),头段容差(≤iv/4)
        // 吸收不了 → 数据完整也误拒,代理补写还会按幻影时刻落库污染序列。锚定行位与中段同构,
        // 判据退化为"首行之前按序还应有几期",与 worker 头缺 slack 判据同口径。
        for (long g = first.fundingTime().getEpochSecond() - headIv; g >= start.getEpochSecond(); g -= headIv) {
            if (g > graceBefore) {
                continue; // 近端宽限:该期可能尚未结算(头段自近及远,不能 break)
            }
            missing.add(Instant.ofEpochSecond(g));
        }
        for (int i = 1; i < settled.size(); i++) {
            FundingRatePeriod a = settled.get(i - 1);
            FundingRatePeriod b = settled.get(i);
            long iv = pairInterval(a, b, fallbackInterval);
            long tol = Math.max(MIN_TOLERANCE_SECONDS, iv / 4);
            long aT = a.fundingTime().getEpochSecond();
            long bT = b.fundingTime().getEpochSecond();
            if (bT - aT <= iv + tol) {
                continue;
            }
            for (long g = aT + iv; g < bT - tol && g < endEpoch; g += iv) {
                if (g > graceBefore) {
                    break;
                }
                missing.add(Instant.ofEpochSecond(g));
            }
        }
        FundingRatePeriod last = settled.get(settled.size() - 1);
        long tailIv = intervalOf(last, fallbackInterval);
        for (long g = last.fundingTime().getEpochSecond() + tailIv; g < endEpoch; g += tailIv) {
            if (g > graceBefore) {
                break; // 近端期次可能尚未结算,不算缺(回测跑到该期时数据已就位或由宽限兜底)
            }
            missing.add(Instant.ofEpochSecond(g));
        }
        return missing;
    }

    private static long intervalOf(FundingRatePeriod p, long fallbackInterval) {
        return p.intervalSeconds() != null && p.intervalSeconds() > 0 ? p.intervalSeconds() : fallbackInterval;
    }

    /** 相邻两行的段 interval:取两行声明的较大者(与 worker max-interval 判据同口径),都缺用 fallback。 */
    private static long pairInterval(FundingRatePeriod a, FundingRatePeriod b, long fallbackInterval) {
        return Math.max(intervalOf(a, fallbackInterval), intervalOf(b, fallbackInterval));
    }

    /**
     * 缺失期次用 BINANCE 同期次已结算值写入本所行(source=PROXY_BINANCE,幂等 upsert)。
     * 反查按容差窗(±max(60s, interval/4))取距网格点最近的 settled 行——Binance 原生行
     * 同样可能有结算时刻漂移,精确键匹配会静默失败且报错误导("Binance 也无数据")。
     */
    private int proxyFillFromBinance(Exchange target, String symbol, List<Instant> missing, long intervalSeconds) {
        long tol = Math.max(MIN_TOLERANCE_SECONDS, intervalSeconds / 4);
        List<FundingRateMapper.FundingRateRow> rows = new ArrayList<>();
        for (Instant grid : missing) {
            FundingRateMapper.FundingRateRow src = fundingRateMapper
                    .findRange(Exchange.BINANCE.name(), symbol, grid.minusSeconds(tol), grid.plusSeconds(tol + 1))
                    .stream()
                    .filter(r -> r.settledRate() != null)
                    .min(java.util.Comparator.comparingLong(
                            r -> Math.abs(r.fundingTime().getEpochSecond() - grid.getEpochSecond())))
                    .orElse(null);
            if (src != null) {
                // 落库时刻 = Binance 源行真实时刻(不写网格点):网格点在漂移序列下可能是幻影时刻,
                // 按它落库产出的行与相邻真行距离超容差,读侧去重吞不掉 → 回测与 paper 双端多收一期
                rows.add(new FundingRateMapper.FundingRateRow(
                        target.name(),
                        symbol,
                        src.fundingTime(),
                        src.settledRate(),
                        null,
                        src.intervalSeconds(),
                        src.markPrice(),
                        "PROXY_BINANCE"));
            }
        }
        if (!rows.isEmpty()) {
            fundingRateMapper.batchUpsert(rows);
        }
        return rows.size();
    }

    private static String buildMissingMessage(
            Exchange exchange, String symbol, Instant start, Instant end, List<Instant> missing, boolean allowProxy) {
        String span =
                "共缺 " + missing.size() + " 期(首缺 " + missing.get(0) + ",末缺 " + missing.get(missing.size() - 1) + ")";
        String remedy;
        if (!allowProxy) {
            remedy = "出路:缩短回测区间,或提交时显式设置 allowFundingProxy=true 用 Binance 同期次值跨所代理"
                    + "(存在跨所基差,报告将标注 PROXY_BINANCE;前端回测栏「资金费代理」开关即此参数)";
        } else if (exchange == Exchange.BINANCE) {
            // 代理源即本所:旧文案谎称"已尝试跨所代理仍缺",实际代码对 BINANCE 任务从不尝试
            remedy = "目标交易所即 Binance,无跨所代理源:请缩短回测区间至资金费历史覆盖窗口";
        } else {
            remedy = "已尝试 Binance 跨所代理仍缺(Binance 同期次也无数据):请缩短回测区间";
        }
        return "PERP 回测资金费序列不完整：" + exchange + " " + symbol + " 区间 [" + start + ", " + end + ") " + span + "。" + remedy;
    }

    /** 供 worker funding-rates 端点复用:已结算行 + 24h 前瞻缓冲(末根 bar 期次可落在任务 end 之后)。 */
    public static Instant fundingQueryEnd(Instant taskEnd) {
        return taskEnd.plus(Duration.ofHours(24));
    }
}
