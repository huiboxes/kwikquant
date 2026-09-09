package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.market.infrastructure.FundingRateMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FundingSeries 纯函数单测:相邻差分派生期次间隔 + 同一逻辑期次双行去重
 * (docs/perp-backtest-spec.md §7 覆盖度语义的读侧防御)。
 */
class FundingSeriesTest {

    private static final long H8 = 28800;
    private static final long H1 = 3600;

    private static Instant t(String s) {
        return Instant.parse(s);
    }

    private static FundingRateMapper.FundingRateRow row(String time, String source, Integer interval) {
        return new FundingRateMapper.FundingRateRow(
                "OKX", "BTC/USDT", t(time), new BigDecimal("0.0001"), null, interval, null, source);
    }

    // ---------- deriveIntervalSeconds ----------

    @Test
    void derive_cleanGrid_returnsInterval() {
        List<Instant> times = List.of(
                t("2025-01-01T00:00:00Z"),
                t("2025-01-01T08:00:00Z"),
                t("2025-01-01T16:00:00Z"),
                t("2025-01-02T00:00:00Z"));
        assertThat(FundingSeries.deriveIntervalSeconds(times)).isEqualTo(H8);
    }

    @Test
    void derive_driftedGrid_clustersToNominal() {
        // ±5min 结算漂移:差分 28500/29100 聚同簇(相对差 ≤25%),取簇最小值
        List<Instant> times = List.of(
                t("2025-01-01T00:00:00Z"),
                t("2025-01-01T08:05:00Z"),
                t("2025-01-01T15:55:00Z"),
                t("2025-01-02T00:03:00Z"));
        Long derived = FundingSeries.deriveIntervalSeconds(times);
        assertThat(derived).isNotNull();
        assertThat(derived).isBetween(28200L, 29400L);
    }

    @Test
    void derive_mixedEra_returnsSmallestGrid() {
        // 历史 1h→8h 期次切换:两簇都重复 ≥2 次,取最小网格(去重窗口不会合并真实 1h 期次)
        List<Instant> times = new ArrayList<>();
        times.add(t("2025-01-01T00:00:00Z"));
        times.add(t("2025-01-01T01:00:00Z"));
        times.add(t("2025-01-01T02:00:00Z"));
        times.add(t("2025-01-01T03:00:00Z"));
        times.add(t("2025-01-01T11:00:00Z"));
        times.add(t("2025-01-01T19:00:00Z"));
        times.add(t("2025-01-02T03:00:00Z"));
        assertThat(FundingSeries.deriveIntervalSeconds(times)).isEqualTo(H1);
    }

    @Test
    void derive_oneHourGridWithNegativeJitter_stillQualifies() {
        // 1h 网格负抖动(结算时刻提前数十秒):差分 3540/3570 低于 1h 硬下限——
        // 候选判定带容差(3540+885 ≥ 3600),整簇不得被剔除,否则 1h 标的全链失活
        List<Instant> times = new ArrayList<>();
        times.add(t("2025-01-01T00:00:00Z"));
        times.add(t("2025-01-01T00:59:00Z"));
        times.add(t("2025-01-01T01:58:30Z"));
        times.add(t("2025-01-01T02:58:00Z"));
        Long derived = FundingSeries.deriveIntervalSeconds(times);
        assertThat(derived).isNotNull();
        assertThat(derived).isBetween(3500L, 3600L);
        assertThat(FundingSeries.hasGridGaps(times)).isFalse();
    }

    @Test
    void derive_tooFewRows_returnsNull() {
        assertThat(FundingSeries.deriveIntervalSeconds(List.of(t("2025-01-01T00:00:00Z"))))
                .isNull();
        assertThat(FundingSeries.deriveIntervalSeconds(List.of(t("2025-01-01T00:00:00Z"), t("2025-01-01T08:00:00Z"))))
                .isNull();
    }

    @Test
    void derive_driftDuplicatesOnly_returnsNull() {
        // 差分全是分钟级漂移(无 ≥1h 重复簇):派生不出,不猜
        List<Instant> times = List.of(
                t("2025-01-01T00:00:00Z"),
                t("2025-01-01T00:03:00Z"),
                t("2025-01-01T00:09:00Z"),
                t("2025-01-01T00:12:00Z"));
        assertThat(FundingSeries.deriveIntervalSeconds(times)).isNull();
    }

    // ---------- dedupeSamePeriod ----------

    @Test
    void dedupe_proxyDoubleRow_prefersExchangeNative() {
        // 同一逻辑期次双行:代理行写精确网格点,原生行带 +3min 漂移。EXCHANGE 真值胜出
        List<FundingRateMapper.FundingRateRow> rows = new ArrayList<>();
        rows.add(row("2025-01-01T00:00:00Z", "EXCHANGE", (int) H8));
        rows.add(row("2025-01-01T08:00:00Z", "PROXY_BINANCE", (int) H8));
        rows.add(row("2025-01-01T08:03:00Z", "EXCHANGE", (int) H8));
        rows.add(row("2025-01-01T16:00:00Z", "EXCHANGE", (int) H8));

        List<FundingRateMapper.FundingRateRow> out = FundingSeries.dedupeSamePeriod(rows);

        assertThat(out).hasSize(3);
        assertThat(out.get(1).fundingTime()).isEqualTo(t("2025-01-01T08:03:00Z"));
        assertThat(out.get(1).source()).isEqualTo("EXCHANGE");
    }

    @Test
    void dedupe_derivedIntervalWhenRowsUndeclared() {
        // 回填形态(interval 全 null):差分派生 8h → 双行仍被合并
        List<FundingRateMapper.FundingRateRow> rows = new ArrayList<>();
        rows.add(row("2025-01-01T00:00:00Z", "EXCHANGE", null));
        rows.add(row("2025-01-01T08:00:00Z", "EXCHANGE", null));
        rows.add(row("2025-01-01T08:02:00Z", "PROXY_BINANCE", null));
        rows.add(row("2025-01-01T16:00:00Z", "EXCHANGE", null));
        rows.add(row("2025-01-02T00:00:00Z", "EXCHANGE", null));

        List<FundingRateMapper.FundingRateRow> out = FundingSeries.dedupeSamePeriod(rows);

        assertThat(out).hasSize(4);
        assertThat(out.stream().filter(FundingSeries::isProxy)).isEmpty();
    }

    @Test
    void dedupe_distinctPeriodsNeverMerged() {
        // 1h 网格真实期次(间隔 3600s > 派生容差):一行不并
        List<FundingRateMapper.FundingRateRow> rows = new ArrayList<>();
        for (int h = 0; h < 6; h++) {
            rows.add(row("2025-01-01T0" + h + ":00:00Z", "EXCHANGE", (int) H1));
        }
        assertThat(FundingSeries.dedupeSamePeriod(rows)).hasSize(6);
    }

    @Test
    void dedupe_underivable_returnsAsIs() {
        // 2 行且无声明 interval:派生不出 → 不去重(消费端语义不变,不猜)
        List<FundingRateMapper.FundingRateRow> rows = List.of(
                row("2025-01-01T00:00:00Z", "EXCHANGE", null), row("2025-01-01T00:03:00Z", "PROXY_BINANCE", null));
        assertThat(FundingSeries.dedupeSamePeriod(rows)).hasSize(2);
    }

    // ---------- hasGridGaps(回填中段洞检测) ----------

    @Test
    void gaps_cleanGrid_none() {
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            times.add(t("2025-01-01T00:00:00Z").plusSeconds(i * 28800L));
        }
        assertThat(FundingSeries.hasGridGaps(times)).isFalse();
    }

    @Test
    void gaps_interiorHole_detected() {
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            times.add(t("2025-01-01T00:00:00Z").plusSeconds(i * 28800L));
        }
        // 洞:跳过一期(16h 差分一次性孤立,不属 8h 网格簇)
        for (int i = 12; i < 20; i++) {
            times.add(t("2025-01-01T00:00:00Z").plusSeconds(i * 28800L));
        }
        assertThat(FundingSeries.hasGridGaps(times)).isTrue();
    }

    @Test
    void gaps_mixedEraGrids_noFalsePositive() {
        // 1h 时代 + 8h 时代并存(历史期次切换):各自差分归属各自簇,转换点 8h 差分归 8h 簇
        List<Instant> times = new ArrayList<>();
        Instant base = t("2025-01-01T00:00:00Z");
        for (int i = 0; i < 8; i++) {
            times.add(base.plusSeconds(i * 3600L));
        }
        Instant switchPoint = base.plusSeconds(7 * 3600L);
        for (int i = 1; i < 8; i++) {
            times.add(switchPoint.plusSeconds(i * 28800L));
        }
        assertThat(FundingSeries.hasGridGaps(times)).isFalse();
    }

    @Test
    void gaps_tooFewRows_conservativelyTrue() {
        assertThat(FundingSeries.hasGridGaps(List.of(t("2025-01-01T00:00:00Z"), t("2025-01-01T08:00:00Z"))))
                .isTrue();
    }

    @Test
    void dedupe_sameSourceKeepsEarliest() {
        List<FundingRateMapper.FundingRateRow> rows = new ArrayList<>();
        rows.add(row("2025-01-01T00:00:00Z", "EXCHANGE", (int) H8));
        rows.add(row("2025-01-01T08:00:00Z", "EXCHANGE", (int) H8));
        rows.add(row("2025-01-01T08:04:00Z", "EXCHANGE", (int) H8));
        rows.add(row("2025-01-01T16:00:00Z", "EXCHANGE", (int) H8));

        List<FundingRateMapper.FundingRateRow> out = FundingSeries.dedupeSamePeriod(rows);

        assertThat(out).hasSize(3);
        assertThat(out.get(1).fundingTime()).isEqualTo(t("2025-01-01T08:00:00Z"));
    }
}
