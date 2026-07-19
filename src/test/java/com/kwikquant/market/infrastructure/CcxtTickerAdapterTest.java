package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link CcxtTickerAdapter} 单测:CCXT 标准化 ticker dict(Map)→ domain Ticker 字段映射 + 边界。
 * 逻辑从 CcxtTickerWorker.convert 抽出,E2E 已验,这里覆盖字段映射/null 兜底/non-Map 抛。
 */
class CcxtTickerAdapterTest {

    private static final Exchange EX = Exchange.OKX;
    private static final MarketType MT = MarketType.SPOT;

    @Test
    void toKwikquant_fullMap_mapsAllFields() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "ETH/USDT");
        raw.put("last", 3000.5);
        raw.put("bid", 3000.4);
        raw.put("ask", 3000.6);
        raw.put("high", 3050.0);
        raw.put("low", 2980.0);
        raw.put("open", 2990.0);
        raw.put("baseVolume", 1234.5);
        raw.put("quoteVolume", 3_700_000.0);
        raw.put("change", 10.5);
        raw.put("percentage", 0.35);
        raw.put("timestamp", 1_700_000_000_000L);

        Ticker t = CcxtTickerAdapter.toKwikquant(raw, EX, MT, "BTC/USDT");

        assertThat(t.exchange()).isEqualTo(EX);
        assertThat(t.marketType()).isEqualTo(MT);
        // raw.symbol 优先于 fallback arg
        assertThat(t.symbol()).isEqualTo("ETH/USDT");
        assertThat(t.last()).isEqualByComparingTo(new BigDecimal("3000.5"));
        assertThat(t.bid()).isEqualByComparingTo(new BigDecimal("3000.4"));
        assertThat(t.ask()).isEqualByComparingTo(new BigDecimal("3000.6"));
        assertThat(t.high()).isEqualByComparingTo(new BigDecimal("3050.0"));
        assertThat(t.low()).isEqualByComparingTo(new BigDecimal("2980.0"));
        assertThat(t.open()).isEqualByComparingTo(new BigDecimal("2990.0"));
        assertThat(t.baseVolume()).isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(t.quoteVolume()).isEqualByComparingTo(new BigDecimal("3700000.0"));
        assertThat(t.change()).isEqualByComparingTo(new BigDecimal("10.5"));
        assertThat(t.percentage()).isEqualByComparingTo(new BigDecimal("0.35"));
        assertThat(t.timestamp()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void toKwikquant_symbolMissing_fallsBackToArg() {
        Map<String, Object> raw = new LinkedHashMap<>();
        // 不放 symbol → 用 fallback arg
        Ticker t = CcxtTickerAdapter.toKwikquant(raw, EX, MT, "BTC/USDT");
        assertThat(t.symbol()).isEqualTo("BTC/USDT");
    }

    @Test
    void toKwikquant_nullNumericFields_mapToNull() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "ETH/USDT");
        raw.put("last", null);
        raw.put("bid", null);
        raw.put("percentage", null);
        Ticker t = CcxtTickerAdapter.toKwikquant(raw, EX, MT, "ETH/USDT");
        // asBd(null) → null(BigDecimal 字段可空,Ticker record compact ctor 不校验 numeric)
        assertThat(t.last()).isNull();
        assertThat(t.bid()).isNull();
        assertThat(t.percentage()).isNull();
        assertThat(t.symbol()).isEqualTo("ETH/USDT");
    }

    @Test
    void toKwikquant_timestampNull_fallsBackToNow() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "ETH/USDT");
        Instant before = Instant.now();
        Ticker t = CcxtTickerAdapter.toKwikquant(raw, EX, MT, "ETH/USDT");
        Instant after = Instant.now();
        // asLong(null) → null → timestamp/receivedAt 兜底 now
        assertThat(t.timestamp()).isBetween(before, after);
        assertThat(t.receivedAt()).isBetween(before, after);
    }

    @Test
    void toKwikquant_nonMap_throwsIllegalArgument() {
        assertThatThrownBy(() -> CcxtTickerAdapter.toKwikquant("not a map", EX, MT, "BTC/USDT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a Map");
    }

    @Test
    void toKwikquant_nullRaw_throwsIllegalArgument() {
        assertThatThrownBy(() -> CcxtTickerAdapter.toKwikquant(null, EX, MT, "BTC/USDT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }
}
