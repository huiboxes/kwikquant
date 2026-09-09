package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.FundingRateHistoryPoint;
import com.kwikquant.market.infrastructure.FundingDataProperties;
import com.kwikquant.market.infrastructure.FundingRateMapper;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FundingRateBackfillServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final String SYMBOL = "BTC/USDT";

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final FundingRateMapper mapper = mock(FundingRateMapper.class);
    private final FundingRateCollector collector = mock(FundingRateCollector.class);

    private static FundingDataProperties props(List<Exchange> exchanges, boolean backfillEnabled) {
        return new FundingDataProperties(
                exchanges,
                List.of(SYMBOL),
                new FundingDataProperties.Collector(false, Duration.ofHours(48)),
                new FundingDataProperties.Backfill(
                        backfillEnabled, Duration.ofDays(90), Instant.parse("2019-09-01T00:00:00Z"), Duration.ZERO));
    }

    private FundingRateBackfillService service(FundingDataProperties props) {
        return new FundingRateBackfillService(marketDataService, mapper, collector, props);
    }

    private static FundingRateHistoryPoint point(Instant t, String rate) {
        return new FundingRateHistoryPoint(SYMBOL, t, new BigDecimal(rate));
    }

    private static FundingRateMapper.Coverage emptyCoverage() {
        return new FundingRateMapper.Coverage(0, null, null, 0);
    }

    @Test
    void backfill_okx_startsAtLookbackWindowAndPaginatesByMaxTimestamp() {
        when(mapper.findCoverage("OKX", SYMBOL)).thenReturn(emptyCoverage());
        Instant p1a = NOW.minus(Duration.ofDays(89));
        Instant p1b = NOW.minus(Duration.ofDays(88));
        Instant p2a = NOW.minus(Duration.ofDays(87));
        when(marketDataService.fetchFundingRateHistory(eq(Exchange.OKX), eq(MarketType.PERP), eq(SYMBOL), any(), any()))
                .thenReturn(List.of(point(p1a, "0.0001"), point(p1b, "0.0002")))
                .thenReturn(List.of(point(p2a, "0.0003")))
                .thenReturn(List.of());

        int total = service(props(List.of(Exchange.OKX), true)).backfill(Exchange.OKX, SYMBOL, NOW);

        assertThat(total).isEqualTo(3);
        ArgumentCaptor<Instant> sinces = ArgumentCaptor.forClass(Instant.class);
        verify(marketDataService, times(3))
                .fetchFundingRateHistory(eq(Exchange.OKX), eq(MarketType.PERP), eq(SYMBOL), sinces.capture(), any());
        // 首页从 now−okxLookback 起,后续按页内 max fundingTime+1ms 推进
        assertThat(sinces.getAllValues().get(0)).isEqualTo(NOW.minus(Duration.ofDays(90)));
        assertThat(sinces.getAllValues().get(1)).isEqualTo(p1b.plusMillis(1));
        assertThat(sinces.getAllValues().get(2)).isEqualTo(p2a.plusMillis(1));
        verify(collector, times(2)).persistSettled(eq(Exchange.OKX), eq(SYMBOL), any());
    }

    @Test
    void backfill_binance_startsAtLongHistorySince() {
        when(mapper.findCoverage("BINANCE", SYMBOL)).thenReturn(emptyCoverage());
        when(marketDataService.fetchFundingRateHistory(eq(Exchange.BINANCE), any(), any(), any(), any()))
                .thenReturn(List.of());

        service(props(List.of(Exchange.BINANCE), true)).backfill(Exchange.BINANCE, SYMBOL, NOW);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(marketDataService)
                .fetchFundingRateHistory(eq(Exchange.BINANCE), eq(MarketType.PERP), eq(SYMBOL), since.capture(), any());
        assertThat(since.getValue()).isEqualTo(Instant.parse("2019-09-01T00:00:00Z"));
    }

    /** 已结算 8h 网格序列行(findRange stub 用;interval 声明留 null = 回填形态)。 */
    private static List<FundingRateMapper.FundingRateRow> settledSeries(
            String exchange, Instant from, Instant to, long stepSeconds) {
        List<FundingRateMapper.FundingRateRow> rows = new java.util.ArrayList<>();
        for (Instant t = from; t.isBefore(to); t = t.plusSeconds(stepSeconds)) {
            rows.add(new FundingRateMapper.FundingRateRow(
                    exchange, SYMBOL, t, new BigDecimal("0.0001"), null, null, null, "EXCHANGE"));
        }
        return rows;
    }

    @Test
    void backfill_coverageComplete_skipsExchangeCalls() {
        when(mapper.findCoverage("OKX", SYMBOL))
                .thenReturn(new FundingRateMapper.Coverage(
                        280, NOW.minus(Duration.ofDays(91)), NOW.minus(Duration.ofHours(6)), 280));
        when(mapper.findRange(eq("OKX"), eq(SYMBOL), any(), any()))
                .thenReturn(
                        settledSeries("OKX", NOW.minus(Duration.ofDays(91)), NOW.minus(Duration.ofHours(6)), 28800));

        int total = service(props(List.of(Exchange.OKX), true)).backfill(Exchange.OKX, SYMBOL, NOW);

        assertThat(total).isZero();
        verify(marketDataService, never()).fetchFundingRateHistory(any(), any(), any(), any(), any());
    }

    @Test
    void backfill_interiorGap_triggersRefetch() {
        // 头尾判定都满足,但中段停机留下 16h 洞(相邻差分不属任何网格簇)→ 必须重拉修复
        Instant from = NOW.minus(Duration.ofDays(91)); // OKX 窗口头判定要求 minTime ≤ since+1d
        List<FundingRateMapper.FundingRateRow> withHole =
                new java.util.ArrayList<>(settledSeries("OKX", from, NOW.minus(Duration.ofDays(5)), 28800));
        withHole.addAll(settledSeries("OKX", NOW.minus(Duration.ofDays(5)).plusSeconds(57600), NOW, 28800));
        when(mapper.findCoverage("OKX", SYMBOL))
                .thenReturn(new FundingRateMapper.Coverage(
                        withHole.size(), from, NOW.minus(Duration.ofHours(6)), withHole.size()));
        when(mapper.findRange(eq("OKX"), eq(SYMBOL), any(), any())).thenReturn(withHole);
        when(marketDataService.fetchFundingRateHistory(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service(props(List.of(Exchange.OKX), true)).backfill(Exchange.OKX, SYMBOL, NOW);

        verify(marketDataService).fetchFundingRateHistory(any(), any(), any(), any(), any());
    }

    @Test
    void backfill_okxSamePeriodDoubleRows_notTreatedAsGap() {
        // OKX 快照派生键(精确网格)与 history 原生键(+3min 漂移)并存:双行的秒级差分不属网格簇,
        // 不去重会让 hasGridGaps 恒真 → 覆盖判定永假 → 每次启动全量重拉且 upsert 无法自愈
        Instant from = NOW.minus(Duration.ofDays(91));
        List<FundingRateMapper.FundingRateRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Instant t = from.plusSeconds(i * 28800L);
            rows.add(new FundingRateMapper.FundingRateRow(
                    "OKX", SYMBOL, t, new BigDecimal("0.0001"), null, null, null, "EXCHANGE"));
            if (i == 10) {
                rows.add(new FundingRateMapper.FundingRateRow(
                        "OKX", SYMBOL, t.plusSeconds(180), new BigDecimal("0.0001"), null, null, null, "EXCHANGE"));
            }
        }
        when(mapper.findCoverage("OKX", SYMBOL))
                .thenReturn(
                        new FundingRateMapper.Coverage(rows.size(), from, NOW.minus(Duration.ofHours(6)), rows.size()));
        when(mapper.findRange(eq("OKX"), eq(SYMBOL), any(), any())).thenReturn(rows);

        int total = service(props(List.of(Exchange.OKX), true)).backfill(Exchange.OKX, SYMBOL, NOW);

        assertThat(total).isZero();
        verify(marketDataService, never()).fetchFundingRateHistory(any(), any(), any(), any(), any());
    }

    @Test
    void backfill_binanceLateListed_notRefetchedEveryRestart() {
        // BINANCE 头部 = 标的上市时刻(2019-09-13 > longHistorySince+1d):旧 minTime 判据恒 false
        // → 每次重启全量重拉近 7 年;新判据头部只对 OKX 生效,序列无洞即跳过
        Instant listing = Instant.parse("2019-09-13T00:00:00Z");
        when(mapper.findCoverage("BINANCE", SYMBOL))
                .thenReturn(new FundingRateMapper.Coverage(9000, listing, NOW.minus(Duration.ofHours(6)), 9000));
        // 只 stub 上市以来近段网格(判定按差分归属,不需要全量 7 年行)
        when(mapper.findRange(eq("BINANCE"), eq(SYMBOL), any(), any()))
                .thenReturn(settledSeries(
                        "BINANCE", NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofHours(6)), 28800));

        int total = service(props(List.of(Exchange.BINANCE), true)).backfill(Exchange.BINANCE, SYMBOL, NOW);

        assertThat(total).isZero();
        verify(marketDataService, never()).fetchFundingRateHistory(any(), any(), any(), any(), any());
    }

    @Test
    void backfill_pageNotAdvancing_breaksAfterFirstPage() {
        when(mapper.findCoverage("OKX", SYMBOL)).thenReturn(emptyCoverage());
        // 交易所返回早于 since 的陈旧数据 → maxTs+1 <= sinceMs,防死循环立即 break
        Instant stale = NOW.minus(Duration.ofDays(95));
        when(marketDataService.fetchFundingRateHistory(any(), any(), any(), any(), any()))
                .thenReturn(List.of(point(stale, "0.0001")));

        int total = service(props(List.of(Exchange.OKX), true)).backfill(Exchange.OKX, SYMBOL, NOW);

        assertThat(total).isEqualTo(1);
        verify(marketDataService, times(1)).fetchFundingRateHistory(any(), any(), any(), any(), any());
    }

    @Test
    void onApplicationReady_disabled_noDbOrExchangeCalls() {
        service(props(List.of(Exchange.OKX), false)).onApplicationReady();

        verify(mapper, never()).findCoverage(any(), any());
        verify(marketDataService, never()).fetchFundingRateHistory(any(), any(), any(), any(), any());
    }

    @Test
    void onApplicationReady_fetchFails_doesNotPropagate() {
        when(mapper.findCoverage("OKX", SYMBOL)).thenReturn(emptyCoverage());
        when(marketDataService.fetchFundingRateHistory(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("network down"));

        assertThatCode(() -> service(props(List.of(Exchange.OKX), true)).onApplicationReady())
                .doesNotThrowAnyException();
    }
}
