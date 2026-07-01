package com.kwikquant.trading.application;

import com.kwikquant.trading.domain.Fill;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Backtest 结果。Wave 7 ReportService 会消费此协议计算夏普、回撤等指标。
 *
 * <p>{@code trades}: 全部成交记录（按时间升序）；{@code equityCurve}: 每个 bar 的权益快照；{@code status}: COMPLETED /
 * FAILED。
 */
public record BacktestResult(
        Long taskId,
        Status status,
        List<Fill> trades,
        List<EquityPoint> equityCurve,
        BigDecimal realizedPnl,
        String errorMessage) {

    public enum Status {
        COMPLETED,
        FAILED
    }

    public record EquityPoint(Instant timestamp, BigDecimal equity) {}
}
