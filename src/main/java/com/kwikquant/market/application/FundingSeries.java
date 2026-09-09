package com.kwikquant.market.application;

import com.kwikquant.market.infrastructure.FundingRateMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 资金费已结算序列的纯函数工具(去重 / 间隔派生),供 {@link MarketDataService} 读侧与
 * {@link FundingCoverageGuard} 预检共用。
 *
 * <p><b>同期次双行</b>:同一逻辑期次可能落两条 settled 行——代理行写精确网格点、交易所原生行
 * 带结算时刻漂移;OKX 快照派生键(fundingTime−interval)与 history 原生键也可并存。主键含时刻,
 * 两行不撞键;消费侧(回测回放 / paper 逐期结算)逐行收费会<b>双计</b>。读侧按派生间隔窗口
 * 去重是一次修复两消费端的最小卡点。
 *
 * <p><b>间隔派生</b>:历史/回填行 {@code interval_seconds} 恒 null(persistSettled 不带 interval),
 * 声明缺失时按相邻期次时刻差分派生:差分聚类(相对差 ≤25% 同簇),取"重复 ≥2 次且达网格候选下限(2880s,1h 网格负抖动容差下界,isGridCandidate)"的最小簇
 * ——多簇并存 = 历史期次切换痕迹(1h/4h/8h),取最小网格保证去重窗口与缺期判定不会合并真实期次;
 * 漂移重复行的差分是分钟级(&lt;1h)或互不相等,不构成候选簇。派生不出(行数 &lt;3 / 无重复簇)返
 * null,调用方保持不去重 / fail-closed,不猜。
 */
final class FundingSeries {

    /** 候选网格间隔下限(秒):交易所资金费网格实际为 1h/4h/8h,更小的差分视为结算时刻漂移。 */
    static final long MIN_GRID_SECONDS = 3600;

    /** 同期次合并容差下限(秒),与 {@link FundingCoverageGuard} 的匹配容差口径一致。 */
    static final long MIN_TOLERANCE_SECONDS = 60;

    private FundingSeries() {}

    /**
     * 相邻差分聚类派生期次间隔(输入 ASC 时刻序列)。返 null = 派生不出(行数 &lt;3 或无
     * "重复 ≥2 次且达网格候选下限"的差分簇,见 isGridCandidate),调用方 fail-closed 或退回声明值,不猜。
     */
    static Long deriveIntervalSeconds(List<Instant> timesAsc) {
        List<long[]> clusters = diffClusters(timesAsc);
        if (clusters == null) {
            return null;
        }
        Long derived = null;
        for (long[] c : clusters) {
            if (isGridCandidate(c[0]) && c[1] >= 2 && (derived == null || c[0] < derived)) {
                derived = c[0];
            }
        }
        return derived;
    }

    /**
     * 中段洞检测(回填 skip 判定用):相邻期次差分全部可归入某个"重复 ≥2 次且达网格候选下限"的网格簇
     * (±25% 相对容差)→ 无洞。历史期次切换(1h/4h/8h)= 多簇并存,各自消化各自时代的差分;
     * 洞(停机 &gt; sweep 窗)产生一次性孤立差分,不属任何簇 → 有洞。行数 &lt;3 或无网格簇 →
     * 判有洞(保守重拉,幂等 upsert 无害)。
     */
    static boolean hasGridGaps(List<Instant> timesAsc) {
        List<long[]> clusters = diffClusters(timesAsc);
        if (clusters == null) {
            return true;
        }
        List<long[]> gridClusters = clusters.stream()
                .filter(c -> isGridCandidate(c[0]) && c[1] >= 2)
                .toList();
        if (gridClusters.isEmpty()) {
            return true;
        }
        List<Long> diffs = adjacentDiffs(timesAsc);
        for (long d : diffs) {
            boolean matched = false;
            for (long[] c : gridClusters) {
                if (Math.abs(d - c[0]) <= Math.max(MIN_TOLERANCE_SECONDS, c[0] / 4)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return true;
            }
        }
        return false;
    }

    /**
     * 网格簇候选判定:簇值 + 其匹配容差触及 1h 网格下限即合格——1h 网格带负抖动(差分如 3540s)
     * 时若按 {@code >= MIN_GRID_SECONDS} 硬下限整簇被剔除,derive/dedupe/gap/enrich 全链失活
     * (1h 标的退化成"每次启动全量重拉 + 双行不去重 + worker 运行期检测失跳")。容差与簇匹配同口径。
     */
    private static boolean isGridCandidate(long clusterMin) {
        return clusterMin + Math.max(MIN_TOLERANCE_SECONDS, clusterMin / 4) >= MIN_GRID_SECONDS;
    }

    /** 相邻差分单链接聚类(相对差 ≤25% 同簇,8h 网格 ±分钟级漂移落同簇)。返 [簇最小值, 重复次数]。 */
    private static List<long[]> diffClusters(List<Instant> timesAsc) {
        if (timesAsc == null || timesAsc.size() < 3) {
            return null;
        }
        List<Long> diffs = adjacentDiffs(timesAsc);
        if (diffs.size() < 2) {
            return null;
        }
        diffs.sort(Long::compareTo);
        List<long[]> clusters = new ArrayList<>();
        long clusterMin = diffs.get(0);
        long clusterPrev = diffs.get(0);
        int clusterCount = 1;
        for (int i = 1; i < diffs.size(); i++) {
            long d = diffs.get(i);
            if (d - clusterPrev <= Math.max(MIN_TOLERANCE_SECONDS, clusterPrev / 4)) {
                clusterCount++;
                clusterPrev = d;
            } else {
                clusters.add(new long[] {clusterMin, clusterCount});
                clusterMin = d;
                clusterPrev = d;
                clusterCount = 1;
            }
        }
        clusters.add(new long[] {clusterMin, clusterCount});
        return clusters;
    }

    private static List<Long> adjacentDiffs(List<Instant> timesAsc) {
        List<Long> diffs = new ArrayList<>();
        for (int i = 1; i < timesAsc.size(); i++) {
            long d = timesAsc.get(i).getEpochSecond() - timesAsc.get(i - 1).getEpochSecond();
            if (d > 0) {
                diffs.add(d);
            }
        }
        return diffs;
    }

    /**
     * 同一逻辑期次去重(输入 settled 行 ASC):相邻行时刻差 ≤ max(60s, interval/4) 视为同期次
     * 双行,保留 source=EXCHANGE 优先(本所真值胜过跨所代理行)、同源保留时刻最早者。interval
     * 优先取派生值(混合期次切换序列下取最小网格,不会合并真实期次),派生不出退行内声明值,
     * 都没有则原样返回(不去重,消费端语义不变)。
     */
    static List<FundingRateMapper.FundingRateRow> dedupeSamePeriod(List<FundingRateMapper.FundingRateRow> rowsAsc) {
        if (rowsAsc == null || rowsAsc.size() < 2) {
            return rowsAsc;
        }
        List<Instant> times = rowsAsc.stream()
                .map(FundingRateMapper.FundingRateRow::fundingTime)
                .toList();
        Long interval = deriveIntervalSeconds(times);
        if (interval == null) {
            interval = rowsAsc.stream()
                    .map(FundingRateMapper.FundingRateRow::intervalSeconds)
                    .filter(i -> i != null && i > 0)
                    .map(Integer::longValue)
                    .findFirst()
                    .orElse(null);
        }
        if (interval == null) {
            return rowsAsc;
        }
        long tolerance = Math.max(MIN_TOLERANCE_SECONDS, interval / 4);
        List<FundingRateMapper.FundingRateRow> kept = new ArrayList<>(rowsAsc.size());
        for (FundingRateMapper.FundingRateRow row : rowsAsc) {
            if (kept.isEmpty()) {
                kept.add(row);
                continue;
            }
            FundingRateMapper.FundingRateRow last = kept.get(kept.size() - 1);
            long diff = row.fundingTime().getEpochSecond() - last.fundingTime().getEpochSecond();
            if (diff > tolerance) {
                kept.add(row);
            } else if (isProxy(last) && !isProxy(row)) {
                // 同期次双行:本所原生行(EXCHANGE)胜过代理行(PROXY_BINANCE)
                kept.set(kept.size() - 1, row);
            }
            // 其余(同源或代理后来者):保留最早行,丢弃重复
        }
        return kept;
    }

    static boolean isProxy(FundingRateMapper.FundingRateRow row) {
        return "PROXY_BINANCE".equals(row.source());
    }

    /**
     * 逐行富化局部间隔:声明缺失(null,回填/历史行常态)的行按 min(前后相邻差分) 补
     * interval_seconds——min 在洞旁取到的是真实网格步长而非洞宽,期次切换边界行取到较细网格
     * (worker 的 max(a,b) 判据在切换点因此宽松,不误报)。差分低于网格候选下限(噪声/残余双行,见 isGridCandidate)不富化,
     * 保持 null = 不猜。worker 运行期缺期检测与 paper 结算 interval 落库都消费此字段,
     * 不富化则纯回填序列的运行期"绝不静默漏收"防线整体失活(interval null 全跳过)。
     */
    static List<FundingRateMapper.FundingRateRow> enrichLocalIntervals(List<FundingRateMapper.FundingRateRow> rowsAsc) {
        if (rowsAsc == null || rowsAsc.size() < 2) {
            return rowsAsc;
        }
        List<FundingRateMapper.FundingRateRow> out = new ArrayList<>(rowsAsc.size());
        for (int i = 0; i < rowsAsc.size(); i++) {
            FundingRateMapper.FundingRateRow row = rowsAsc.get(i);
            if (row.intervalSeconds() != null && row.intervalSeconds() > 0) {
                out.add(row);
                continue;
            }
            long prev = i > 0
                    ? row.fundingTime().getEpochSecond()
                            - rowsAsc.get(i - 1).fundingTime().getEpochSecond()
                    : Long.MAX_VALUE;
            long next = i < rowsAsc.size() - 1
                    ? rowsAsc.get(i + 1).fundingTime().getEpochSecond()
                            - row.fundingTime().getEpochSecond()
                    : Long.MAX_VALUE;
            long local = Math.min(prev, next);
            if (local < Long.MAX_VALUE && isGridCandidate(local) && local < Integer.MAX_VALUE) {
                out.add(new FundingRateMapper.FundingRateRow(
                        row.exchange(),
                        row.symbol(),
                        row.fundingTime(),
                        row.settledRate(),
                        row.predictedRate(),
                        (int) local,
                        row.markPrice(),
                        row.source()));
            } else {
                out.add(row);
            }
        }
        return out;
    }
}
