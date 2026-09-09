package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.market.domain.FundingRatePeriod;
import com.kwikquant.market.infrastructure.FundingRateMapper;
import com.kwikquant.shared.types.Exchange;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FundingCoverageGuard 单测:期望期次网格(epoch 对齐,interval 取自序列行)对照已结算行,
 * 缺期 fail-closed 拒;容差 interval/4(最小 60s);近端宽限 1 interval;显式 allowProxy 走
 * Binance 同期次代理补写(source=PROXY_BINANCE)。
 */
class FundingCoverageGuardTest {

    private static final String SYMBOL = "BTC/USDT";
    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2025-01-03T00:00:00Z");
    private static final Instant NOW = Instant.parse("2025-01-10T00:00:00Z");
    private static final long H8 = 28800;

    private MarketDataService marketDataService;
    private FundingRateMapper fundingRateMapper;
    private FundingCoverageGuard guard;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        fundingRateMapper = mock(FundingRateMapper.class);
        guard = new FundingCoverageGuard(marketDataService, fundingRateMapper);
    }

    /** 每 8h 一期的完整已结算序列([START, END) 6 期)。 */
    private static List<FundingRatePeriod> fullSeries() {
        return series(
                "2025-01-01T00:00:00Z",
                "2025-01-01T08:00:00Z",
                "2025-01-01T16:00:00Z",
                "2025-01-02T00:00:00Z",
                "2025-01-02T08:00:00Z",
                "2025-01-02T16:00:00Z");
    }

    private static List<FundingRatePeriod> series(String... times) {
        List<FundingRatePeriod> out = new ArrayList<>();
        for (String t : times) {
            out.add(new FundingRatePeriod(
                    Instant.parse(t), new BigDecimal("0.0001"), null, (int) H8, new BigDecimal("60000"), "EXCHANGE"));
        }
        return out;
    }

    private void stubSeries(List<FundingRatePeriod> settled) {
        when(marketDataService.findSettledFundingPeriods(Exchange.OKX, SYMBOL, START, END))
                .thenReturn(settled);
    }

    @Test
    void fullCoverage_returnsResult() {
        stubSeries(fullSeries());

        FundingCoverageGuard.CoverageResult r = guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW);

        assertThat(r.settledPeriods()).isEqualTo(6);
        assertThat(r.proxyApplied()).isZero();
    }

    @Test
    void emptySeries_rejectsFailClosed() {
        stubSeries(List.of());

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无任何已结算资金费序列");
    }

    @Test
    void missingIntervalDeclaration_rejects() {
        List<FundingRatePeriod> noInterval =
                List.of(new FundingRatePeriod(START, new BigDecimal("0.0001"), null, null, null, "EXCHANGE"));
        stubSeries(noInterval);

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalSeconds")
                .hasMessageContaining("fail-closed");
    }

    @Test
    void missingPeriod_rejectsWithSpanAndRemedy() {
        List<FundingRatePeriod> withGap = new ArrayList<>(fullSeries());
        withGap.remove(2); // 2025-01-01T16:00:00Z 缺
        stubSeries(withGap);

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("共缺 1 期")
                .hasMessageContaining("首缺 2025-01-01T16:00:00Z")
                .hasMessageContaining("allowFundingProxy=true");
        // 未显式允许代理:绝不写库
        verify(fundingRateMapper, never()).findRange(any(), any(), any(), any());
        verify(fundingRateMapper, never()).batchUpsert(any());
    }

    @Test
    void recentPeriodWithinGrace_notCountedMissing() {
        // now = 01-02T20:00 → 宽限线 now−8h = 01-02T12:00;网格点 01-02T16:00 在宽限内不算缺
        Instant now = Instant.parse("2025-01-02T20:00:00Z");
        stubSeries(series(
                "2025-01-01T00:00:00Z",
                "2025-01-01T08:00:00Z",
                "2025-01-01T16:00:00Z",
                "2025-01-02T00:00:00Z",
                "2025-01-02T08:00:00Z"));

        FundingCoverageGuard.CoverageResult r = guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, now);

        assertThat(r.settledPeriods()).isEqualTo(5);
    }

    @Test
    void toleranceCoversDriftedPeriodTimes() {
        // 8h interval 容差 = 2h:整批行漂移 +1h 仍算覆盖(交易所期次时刻历史漂移)
        stubSeries(series(
                "2025-01-01T01:00:00Z",
                "2025-01-01T09:00:00Z",
                "2025-01-01T17:00:00Z",
                "2025-01-02T01:00:00Z",
                "2025-01-02T09:00:00Z",
                "2025-01-02T17:00:00Z"));

        assertThat(guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW)
                        .settledPeriods())
                .isEqualTo(6);
    }

    @Test
    void consistentDrift_seriesSelfConsistent_noMissing() {
        // 整批行 +3h 漂移但间距仍 8h:序列自洽(判据锚定行位,不锚绝对 epoch 网格)。
        // 回测期次归属按行时刻结算,无真实缺期——旧 epoch 对齐头段网格对此形态误拒
        // (抖动派生 interval 下错位可达整个 interval,容差吸收不了),且头段漂移零覆盖
        stubSeries(series(
                "2025-01-01T03:00:00Z",
                "2025-01-01T11:00:00Z",
                "2025-01-01T19:00:00Z",
                "2025-01-02T03:00:00Z",
                "2025-01-02T11:00:00Z",
                "2025-01-02T19:00:00Z"));

        assertThat(guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW)
                        .settledPeriods())
                .isEqualTo(6);
    }

    @Test
    void headGapBeforeFirstRow_countsMissing() {
        // 首行 11:00,锚定步进回 03:00 ≥ start(00:00) → 头部真缺 1 期(旧逻辑靠 epoch 对齐
        // 碰巧也能抓这种,新逻辑靠锚定行位——真缺期检出能力不降级)
        stubSeries(series(
                "2025-01-01T11:00:00Z",
                "2025-01-01T19:00:00Z",
                "2025-01-02T03:00:00Z",
                "2025-01-02T11:00:00Z",
                "2025-01-02T19:00:00Z"));

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("共缺 1 期")
                .hasMessageContaining("首缺 2025-01-01T03:00:00Z");
    }

    @Test
    void allowProxy_fillsFromBinanceAndPasses() {
        List<FundingRatePeriod> withGap = new ArrayList<>(fullSeries());
        withGap.remove(2); // 01-01T16:00 缺
        Instant missingTime = Instant.parse("2025-01-01T16:00:00Z");
        when(marketDataService.findSettledFundingPeriods(Exchange.OKX, SYMBOL, START, END))
                .thenReturn(withGap)
                .thenReturn(fullSeries()); // 代理补写后重查通过
        when(fundingRateMapper.findRange(eq("BINANCE"), eq(SYMBOL), any(), any()))
                .thenReturn(List.of(new FundingRateMapper.FundingRateRow(
                        "BINANCE",
                        SYMBOL,
                        missingTime,
                        new BigDecimal("0.00012"),
                        null,
                        (int) H8,
                        new BigDecimal("59000"),
                        "EXCHANGE")));

        FundingCoverageGuard.CoverageResult r = guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, true, NOW);

        assertThat(r.proxyApplied()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FundingRateMapper.FundingRateRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(fundingRateMapper).batchUpsert(cap.capture());
        FundingRateMapper.FundingRateRow written = cap.getValue().get(0);
        assertThat(written.exchange()).isEqualTo("OKX"); // 写目标所(消费方按任务 exchange 查)
        assertThat(written.settledRate()).isEqualByComparingTo("0.00012");
        assertThat(written.source()).isEqualTo("PROXY_BINANCE");
        assertThat(written.fundingTime()).isEqualTo(missingTime);
    }

    @Test
    void allowProxy_binanceAlsoMissing_stillRejects() {
        List<FundingRatePeriod> withGap = new ArrayList<>(fullSeries());
        withGap.remove(2);
        when(marketDataService.findSettledFundingPeriods(Exchange.OKX, SYMBOL, START, END))
                .thenReturn(withGap);
        when(fundingRateMapper.findRange(eq("BINANCE"), eq(SYMBOL), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, true, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已尝试 Binance 跨所代理仍缺")
                .hasMessageContaining("缩短回测区间");
        verify(fundingRateMapper, never()).batchUpsert(any());
    }

    @Test
    void allowProxy_binanceTaskItself_noProxySource() {
        // BINANCE 任务自身缺期:代理源即本所,无代理可言 → 直接拒,不查不写
        Instant start = START;
        when(marketDataService.findSettledFundingPeriods(Exchange.BINANCE, SYMBOL, start, END))
                .thenReturn(series("2025-01-01T00:00:00Z"));

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.BINANCE, SYMBOL, start, END, true, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("共缺");
        verify(fundingRateMapper, never()).findRange(any(), any(), any(), any());
        verify(fundingRateMapper, never()).batchUpsert(any());
    }

    @Test
    void fundingQueryEnd_adds24hBuffer() {
        // worker funding-rates 端点查询缓冲:末根 bar 期次可落在任务 end 之后(左开右闭归属)
        assertThat(FundingCoverageGuard.fundingQueryEnd(END)).isEqualTo(END.plusSeconds(86400));
    }

    // ---------- interval 派生回退(P1-4:回填行 interval_seconds 恒 null) ----------

    private static List<FundingRatePeriod> seriesNoInterval(String... times) {
        List<FundingRatePeriod> out = new ArrayList<>();
        for (String t : times) {
            out.add(new FundingRatePeriod(
                    Instant.parse(t), new BigDecimal("0.0001"), null, null, new BigDecimal("60000"), "EXCHANGE"));
        }
        return out;
    }

    @Test
    void noIntervalDeclaration_derivedFromAdjacentDiffs_passes() {
        // 回填形态:8h 网格 6 行,声明 interval 全 null → 相邻差分派生 28800,不再系统性误拒
        stubSeries(seriesNoInterval(
                "2025-01-01T00:00:00Z",
                "2025-01-01T08:00:00Z",
                "2025-01-01T16:00:00Z",
                "2025-01-02T00:00:00Z",
                "2025-01-02T08:00:00Z",
                "2025-01-02T16:00:00Z"));

        FundingCoverageGuard.CoverageResult r = guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW);

        assertThat(r.settledPeriods()).isEqualTo(6);
        assertThat(r.proxyApplied()).isZero();
    }

    @Test
    void noIntervalDeclaration_tooFewRowsToDerive_rejects() {
        // 2 行派生不出(差分簇需重复 ≥2 次):fail-closed,错误信息说明两条判据都失败
        stubSeries(seriesNoInterval("2025-01-01T00:00:00Z", "2025-01-01T08:00:00Z"));

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalSeconds")
                .hasMessageContaining("差分派生")
                .hasMessageContaining("fail-closed");
    }

    // ---------- 分段网格(P2-9:历史 4h→8h 期次切换) ----------

    @Test
    void intervalSwitch_segmentedGridNoFalseMissing() {
        // 前段 4h 网格(声明 14400),后段 8h 网格(声明 28800):旧版按首行 interval=4h 生成
        // 全区间网格,8h 段每隔一点误判缺期;分段后逐 gap 按两行声明较大者判定,不误拒
        List<FundingRatePeriod> mixed = new ArrayList<>();
        for (String t : List.of(
                "2025-01-01T00:00:00Z", "2025-01-01T04:00:00Z", "2025-01-01T08:00:00Z", "2025-01-01T12:00:00Z")) {
            mixed.add(new FundingRatePeriod(
                    Instant.parse(t), new BigDecimal("0.0001"), null, 14400, new BigDecimal("60000"), "EXCHANGE"));
        }
        for (String t : List.of(
                "2025-01-01T20:00:00Z", "2025-01-02T04:00:00Z", "2025-01-02T12:00:00Z", "2025-01-02T20:00:00Z")) {
            mixed.add(new FundingRatePeriod(
                    Instant.parse(t), new BigDecimal("0.0001"), null, 28800, new BigDecimal("60000"), "EXCHANGE"));
        }
        stubSeries(mixed);

        FundingCoverageGuard.CoverageResult r = guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW);

        assertThat(r.settledPeriods()).isEqualTo(8);
    }

    @Test
    void intervalSwitch_realGapInWideSegment_detected() {
        // 8h 段真缺一期(01-02T04:00 缺失,gap 16h > 8h+2h):分段判定仍能查出,不漏检
        List<FundingRatePeriod> mixed = new ArrayList<>();
        for (String t : List.of(
                "2025-01-01T00:00:00Z", "2025-01-01T04:00:00Z", "2025-01-01T08:00:00Z", "2025-01-01T12:00:00Z")) {
            mixed.add(new FundingRatePeriod(
                    Instant.parse(t), new BigDecimal("0.0001"), null, 14400, new BigDecimal("60000"), "EXCHANGE"));
        }
        for (String t : List.of("2025-01-01T20:00:00Z", "2025-01-02T12:00:00Z", "2025-01-02T20:00:00Z")) {
            mixed.add(new FundingRatePeriod(
                    Instant.parse(t), new BigDecimal("0.0001"), null, 28800, new BigDecimal("60000"), "EXCHANGE"));
        }
        stubSeries(mixed);

        assertThatThrownBy(() -> guard.ensureCoverage(Exchange.OKX, SYMBOL, START, END, false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("共缺 1 期")
                .hasMessageContaining("首缺 2025-01-02T04:00:00Z");
    }
}
