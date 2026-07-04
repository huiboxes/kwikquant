package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "报告名称", example = "BTC/USDT 网格回测", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 200, message = "name must not exceed 200 characters")
                String name,
        @Schema(description = "回测参数键值对（策略入参快照，≤100 项）", example = "{\"gridNum\":10,\"upper\":50000}")
                @Size(max = 100, message = "params must not exceed 100 entries")
                Map<String, Object> params,
        @Schema(
                        description = "canonical symbol，BASE/QUOTE 格式",
                        example = "BTC/USDT",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Pattern(regexp = "^[A-Z0-9]+/[A-Z0-9]+$", message = "symbol must match BASE/QUOTE format")
                String symbol,
        @Schema(description = "时间周期", example = "1h", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank
                @Size(max = 8, message = "timeframe must not exceed 8 characters")
                String timeframe,
        @Schema(description = "回测时间区间", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull @Valid PeriodRange period,
        @Schema(description = "交易明细列表（1-10000 条）", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotEmpty
                @Size(max = 10000, message = "trades must not exceed 10000")
                List<@Valid TradeEntry> trades,
        @Schema(description = "权益曲线点列表（≤50000 个）")
                @Size(max = 50000, message = "equityCurve must not exceed 50000 points")
                List<@Valid EquityPointEntry> equityCurve) {

    public record PeriodRange(
            @Schema(description = "区间起始", example = "2026-06-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Instant start,
            @Schema(description = "区间结束", example = "2026-07-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Instant end) {}

    public record TradeEntry(
            @Schema(description = "成交时间", example = "2026-06-15T08:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Instant time,
            @Schema(description = "方向（枚举: buy | sell）", example = "buy", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotBlank
                    @Pattern(regexp = "^(buy|sell)$", message = "side must be buy or sell")
                    String side,
            @Schema(
                            description = "成交价格（必须 >0，精度 8 位）",
                            example = "42150.50",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    BigDecimal price,
            @Schema(description = "成交数量（必须 >0，精度 8 位）", example = "0.0025", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    BigDecimal amount,
            @Schema(description = "手续费（精度 8 位，为空则后端按 0 处理）", example = "0.0052") BigDecimal fee) {}

    public record EquityPointEntry(
            @Schema(description = "时间点", example = "2026-06-15T08:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Instant time,
            @Schema(description = "权益（USDT，精度 2 位）", example = "10532.18", requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    BigDecimal equity) {}
}
