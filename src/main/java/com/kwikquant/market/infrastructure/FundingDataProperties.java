package com.kwikquant.market.infrastructure;

import com.kwikquant.shared.types.Exchange;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资金费率期次序列（funding_rates 表）的采集与回填配置。
 *
 * <p>采集周期不在本 record 内：{@code @Scheduled} 直接读 {@code kwikquant.funding.collector.interval-ms}
 * 占位符（fixedDelayString 只认毫秒/ISO-8601，yaml 的 Duration 简写走 Boot 绑定、两边格式不通用）。
 */
@ConfigurationProperties(prefix = "kwikquant.funding")
public record FundingDataProperties(
        List<Exchange> exchanges, List<String> symbols, Collector collector, Backfill backfill) {

    public FundingDataProperties {
        if (exchanges == null) exchanges = List.of(Exchange.OKX, Exchange.BINANCE);
        if (symbols == null) symbols = List.of("BTC/USDT", "ETH/USDT", "SOL/USDT");
        if (collector == null) collector = new Collector(null, null);
        if (backfill == null) backfill = new Backfill(null, null, null, null);
    }

    public record Collector(Boolean enabled, Duration settledSweepLookback) {
        public Collector {
            if (enabled == null) enabled = true;
            if (settledSweepLookback == null) settledSweepLookback = Duration.ofHours(48);
        }
    }

    /**
     * 一次性回填配置。OKX funding-rate-history 仅回溯约 94 天且窗口外数据永久流失，回填范围取保守
     * {@code okxLookback}；其余所（Binance fapi 近全历史，2019 起可取）从 {@code longHistorySince} 起。
     */
    public record Backfill(Boolean enabled, Duration okxLookback, Instant longHistorySince, Duration pagePause) {
        public Backfill {
            if (enabled == null) enabled = true;
            if (okxLookback == null) okxLookback = Duration.ofDays(90);
            if (longHistorySince == null) longHistorySince = Instant.parse("2019-09-01T00:00:00Z");
            if (pagePause == null) pagePause = Duration.ofMillis(200);
        }
    }
}
