package com.kwikquant.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.market.domain.FundingRate;
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

class FundingRateCollectorTest {

    private static final Instant FUNDING_TIME = Instant.parse("2026-09-07T08:00:00Z");
    private static final Instant SETTLED_TIME = Instant.parse("2026-09-07T00:00:00Z");

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final FundingRateMapper mapper = mock(FundingRateMapper.class);

    private static FundingDataProperties props(boolean enabled, List<String> symbols) {
        return new FundingDataProperties(
                List.of(Exchange.OKX),
                symbols,
                new FundingDataProperties.Collector(enabled, Duration.ofHours(48)),
                new FundingDataProperties.Backfill(false, null, null, null));
    }

    /** OKX 真实形态:unified markPrice 恒 null,info 带上期已结算值,interval=8h。 */
    private static FundingRate okxSnapshot(String symbol) {
        return new FundingRate(
                Exchange.OKX,
                MarketType.PERP,
                symbol,
                new BigDecimal("0.0001"),
                FUNDING_TIME,
                28800,
                new BigDecimal("-0.0000135"),
                null,
                null,
                null,
                null,
                Instant.now());
    }

    @Test
    void collect_okxSnapshot_upsertsPredictedAndSettledRows_thenSweepsHistory() {
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(okxSnapshot("BTC/USDT"));
        when(marketDataService.fetchFundingRateHistory(
                        eq(Exchange.OKX), eq(MarketType.PERP), eq("BTC/USDT"), any(), any()))
                .thenReturn(List.of(new FundingRateHistoryPoint("BTC/USDT", SETTLED_TIME, new BigDecimal("0.00002"))));

        new FundingRateCollector(marketDataService, mapper, props(true, List.of("BTC/USDT"))).collect();

        ArgumentCaptor<FundingRateMapper.FundingRateRow> rows =
                ArgumentCaptor.forClass(FundingRateMapper.FundingRateRow.class);
        verify(mapper, times(2)).upsert(rows.capture());
        // ① predicted 行:期次键=fundingTime(未来结算时刻)
        assertThat(rows.getAllValues().get(0).fundingTime()).isEqualTo(FUNDING_TIME);
        assertThat(rows.getAllValues().get(0).predictedRate()).isEqualByComparingTo("0.0001");
        assertThat(rows.getAllValues().get(0).settledRate()).isNull();
        assertThat(rows.getAllValues().get(0).intervalSeconds()).isEqualTo(28800);
        // ② settled 行:期次键=fundingTime−interval,与 predicted 不是同一期
        assertThat(rows.getAllValues().get(1).fundingTime()).isEqualTo(SETTLED_TIME);
        assertThat(rows.getAllValues().get(1).settledRate()).isEqualByComparingTo("-0.0000135");
        assertThat(rows.getAllValues().get(1).predictedRate()).isNull();

        // ③ history sweep 落 settled 行
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FundingRateMapper.FundingRateRow>> batch = ArgumentCaptor.forClass(List.class);
        verify(mapper).batchUpsert(batch.capture());
        assertThat(batch.getValue()).singleElement().satisfies(r -> {
            assertThat(r.fundingTime()).isEqualTo(SETTLED_TIME);
            assertThat(r.settledRate()).isEqualByComparingTo("0.00002");
            assertThat(r.source()).isEqualTo("EXCHANGE");
        });
    }

    @Test
    void collect_disabled_makesNoExchangeOrDbCalls() {
        new FundingRateCollector(marketDataService, mapper, props(false, List.of("BTC/USDT"))).collect();

        verify(marketDataService, never()).fetchFundingRate(any(), any(), any());
        verify(mapper, never()).upsert(any());
    }

    @Test
    void collect_snapshotWithoutPeriodKey_skipsUpsert_butStillSweepsHistory() {
        FundingRate noPeriod = new FundingRate(
                Exchange.OKX,
                MarketType.PERP,
                "BTC/USDT",
                new BigDecimal("0.0001"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now());
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(noPeriod);
        when(marketDataService.fetchFundingRateHistory(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new FundingRateHistoryPoint("BTC/USDT", SETTLED_TIME, BigDecimal.ONE)));

        new FundingRateCollector(marketDataService, mapper, props(true, List.of("BTC/USDT"))).collect();

        verify(mapper, never()).upsert(any());
        verify(mapper).batchUpsert(any());
    }

    @Test
    void collect_oneSymbolFails_remainingSymbolsStillCollected() {
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenThrow(new RuntimeException("network down"));
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "ETH/USDT"))
                .thenReturn(okxSnapshot("ETH/USDT"));
        when(marketDataService.fetchFundingRateHistory(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        new FundingRateCollector(marketDataService, mapper, props(true, List.of("BTC/USDT", "ETH/USDT"))).collect();

        // ETH 不受 BTC 失败影响:predicted+settled 两次 upsert
        verify(mapper, times(2)).upsert(any());
    }

    @Test
    void collect_sweepWindow_usesConfiguredLookback() {
        when(marketDataService.fetchFundingRate(Exchange.OKX, MarketType.PERP, "BTC/USDT"))
                .thenReturn(okxSnapshot("BTC/USDT"));
        when(marketDataService.fetchFundingRateHistory(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        new FundingRateCollector(marketDataService, mapper, props(true, List.of("BTC/USDT"))).collect();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(marketDataService)
                .fetchFundingRateHistory(
                        eq(Exchange.OKX), eq(MarketType.PERP), eq("BTC/USDT"), since.capture(), until.capture());
        assertThat(Duration.between(since.getValue(), until.getValue())).isEqualTo(Duration.ofHours(48));
    }
}
