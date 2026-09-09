package com.kwikquant.report.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 权益曲线点。
 *
 * <p>PERP 报告(docs/perp-backtest-spec.md §8)额外携带 {@code marginUsed}(已用保证金,
 * ISOLATED 为仓位保证金、CROSS 恒 0)与 {@code fundingCum}(累计资金费,净收入可为负);
 * SPOT 报告两者为 null。
 */
public record EquityPoint(Instant time, BigDecimal equity, BigDecimal marginUsed, BigDecimal fundingCum) {

    /** SPOT 便捷构造(无 PERP 扩展列)。 */
    public EquityPoint(Instant time, BigDecimal equity) {
        this(time, equity, null, null);
    }
}
