package com.kwikquant.report.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BacktestSubmitRequest(
        @NotBlank @Size(max = 200, message = "name must not exceed 200 characters") String name,
        @Size(max = 100, message = "params must not exceed 100 entries") Map<String, Object> params,
        @NotBlank @Pattern(regexp = "^[A-Z0-9]+/[A-Z0-9]+$", message = "symbol must match BASE/QUOTE format")
                String symbol,
        @NotBlank @Size(max = 8, message = "timeframe must not exceed 8 characters") String timeframe,
        @NotNull @Valid PeriodRange period,
        @NotEmpty @Size(max = 10000, message = "trades must not exceed 10000") List<@Valid TradeEntry> trades,
        @Size(max = 50000, message = "equityCurve must not exceed 50000 points")
                List<@Valid EquityPointEntry> equityCurve) {

    public record PeriodRange(@NotNull Instant start, @NotNull Instant end) {}

    public record TradeEntry(
            @NotNull Instant time,
            @NotBlank @Pattern(regexp = "^(buy|sell)$", message = "side must be buy or sell") String side,
            @NotNull BigDecimal price,
            @NotNull BigDecimal amount,
            BigDecimal fee) {}

    public record EquityPointEntry(@NotNull Instant time, @NotNull BigDecimal equity) {}
}
