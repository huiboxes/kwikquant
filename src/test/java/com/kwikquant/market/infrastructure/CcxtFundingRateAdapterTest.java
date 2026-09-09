package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.market.domain.FundingRateHistoryPoint;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CcxtFundingRateAdapterTest {

    private static io.github.ccxt.types.FundingRate ccxtFr() {
        return new io.github.ccxt.types.FundingRate((Object) null);
    }

    @Test
    void toKwikquant_okxStyle_mapsPeriodKeyIntervalAndSettledRate() {
        var fr = ccxtFr();
        fr.fundingRate = 0.0001;
        // OKX unified markPrice/timestamp 恒 null(raw 无 markPx), settled 只存活于 info
        fr.fundingTimestamp = 1_700_000_000_000L;
        fr.nextFundingTimestamp = 1_700_028_800_000L;
        fr.interval = "8h";
        fr.info = Map.of("settFundingRate", "-0.0000135", "fundingTime", "1700000000000");

        FundingRate result = CcxtFundingRateAdapter.toKwikquant(fr, Exchange.OKX, MarketType.PERP, "BTC/USDT");

        assertThat(result.fundingRate()).isEqualByComparingTo("0.0001");
        assertThat(result.markPrice()).isNull();
        assertThat(result.fundingTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(result.intervalSeconds()).isEqualTo(28800);
        assertThat(result.settledFundingRate()).isEqualByComparingTo("-0.0000135");
        assertThat(result.settledFundingTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L - 28_800_000L));
        assertThat(result.nextFundingTime()).isEqualTo(Instant.ofEpochMilli(1_700_028_800_000L));
    }

    @Test
    void toKwikquant_binanceStyle_noSettledRateButPeriodDerivable() {
        var fr = ccxtFr();
        fr.fundingRate = 0.0001;
        fr.markPrice = 60000.5;
        fr.fundingTimestamp = 1_700_000_000_000L;
        fr.interval = "8h";
        fr.info = null;

        FundingRate result = CcxtFundingRateAdapter.toKwikquant(fr, Exchange.BINANCE, MarketType.PERP, "BTC/USDT");

        assertThat(result.settledFundingRate()).isNull();
        assertThat(result.settledFundingTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L - 28_800_000L));
        assertThat(result.markPrice()).isEqualByComparingTo("60000.5");
    }

    @Test
    void toKwikquant_missingPeriodFields_nullSafe() {
        var fr = ccxtFr();
        fr.fundingRate = 0.0001;

        FundingRate result = CcxtFundingRateAdapter.toKwikquant(fr, Exchange.OKX, MarketType.PERP, "BTC/USDT");

        assertThat(result.fundingTime()).isNull();
        assertThat(result.intervalSeconds()).isNull();
        assertThat(result.settledFundingRate()).isNull();
        assertThat(result.settledFundingTime()).isNull();
    }

    @Test
    void toKwikquant_nonNumericSettledRate_yieldsNull() {
        var fr = ccxtFr();
        fr.fundingTimestamp = 1_700_000_000_000L;
        fr.interval = "8h";
        fr.info = Map.of("settFundingRate", "not-a-number");

        FundingRate result = CcxtFundingRateAdapter.toKwikquant(fr, Exchange.OKX, MarketType.PERP, "BTC/USDT");

        assertThat(result.settledFundingRate()).isNull();
    }

    @Test
    void parseIntervalSeconds_coversHourMinuteDayAndGarbage() {
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("8h")).isEqualTo(28800);
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("1h")).isEqualTo(3600);
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("4H")).isEqualTo(14400);
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("30m")).isEqualTo(1800);
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("1d")).isEqualTo(86400);
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds(null)).isNull();
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("")).isNull();
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("0.5h")).isNull();
        assertThat(CcxtFundingRateAdapter.parseIntervalSeconds("weekly")).isNull();
    }

    @Test
    void toHistoryPoints_parsesTypedElementsAndMaps_skipsIncomplete() {
        var typed = new io.github.ccxt.types.FundingRateHistory((Object) null);
        typed.timestamp = 1_699_999_000_000L;
        typed.fundingRate = 0.0000135;
        // timestamp/rate 缺失 → 跳过(宁缺勿存脏期次键)
        var incomplete = new io.github.ccxt.types.FundingRateHistory((Object) null);
        Map<String, Object> asMap = Map.of("timestamp", 1_699_970_000_000L, "fundingRate", "-0.00001");

        List<FundingRateHistoryPoint> points =
                CcxtFundingRateAdapter.toHistoryPoints(List.of(typed, incomplete, asMap, "junk"), "BTC/USDT");

        assertThat(points).hasSize(2);
        assertThat(points.get(0).symbol()).isEqualTo("BTC/USDT");
        assertThat(points.get(0).fundingTime()).isEqualTo(Instant.ofEpochMilli(1_699_999_000_000L));
        assertThat(points.get(0).rate()).isEqualByComparingTo("0.0000135");
        assertThat(points.get(1).rate()).isEqualByComparingTo("-0.00001");
    }

    @Test
    void toHistoryPoints_nonListRaw_yieldsEmpty() {
        assertThat(CcxtFundingRateAdapter.toHistoryPoints(null, "BTC/USDT")).isEmpty();
        assertThat(CcxtFundingRateAdapter.toHistoryPoints(Map.of(), "BTC/USDT")).isEmpty();
    }
}
