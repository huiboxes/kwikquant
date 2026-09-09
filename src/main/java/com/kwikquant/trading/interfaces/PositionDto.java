package com.kwikquant.trading.interfaces;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 持仓响应 DTO。
 *
 * <p>金额/数量字段序列化为 decimal string（金额红线：消费方直接十进制解析，不经 JSON
 * number/float 中转——worker RunnerContext 与前端 money.ts 同源纪律）。
 */
public record PositionDto(
        @Schema(description = "持仓 ID", example = "128") Long positionId,
        @Schema(description = "账户 ID", example = "7") Long accountId,
        @Schema(description = "canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(
                        description = "持仓方向（小写枚举: long | short | flat；PERP 桶方向看 positionSide 大写 LONG | SHORT）",
                        example = "long")
                String side,
        @Schema(type = "string", description = "持仓数量（decimal string，精度 8 位）", example = "0.0025")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal qty,
        @Schema(type = "string", description = "平均开仓价（decimal string，精度 8 位）", example = "42150.50")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal avgEntryPrice,
        @Schema(type = "string", description = "已实现盈亏（decimal string，USDT 估值口径，精度 2 位，负值为亏损）", example = "32.15")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal realizedPnl,
        @Schema(
                        type = "string",
                        nullable = true,
                        description = "未实现盈亏（decimal string，USDT 估值口径，精度 2 位）。行情不可用时为 null",
                        example = "15.30")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal unrealizedPnl,
        @Schema(
                        type = "string",
                        nullable = true,
                        description = "当前市价（decimal string）。行情不可用时为 null",
                        example = "42300.00")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal currentPrice,
        @Schema(description = "版本号（乐观锁）", example = "1") Long version,
        @Schema(description = "合约杠杆倍数（PERP,SPOT null）", example = "10") Integer leverage,
        @Schema(description = "合约保证金模式（PERP: ISOLATED | CROSS,SPOT null）", example = "ISOLATED") String marginMode,
        @Schema(description = "合约持仓方向（PERP: LONG | SHORT,SPOT null）", example = "LONG") String positionSide,
        @Schema(
                        type = "string",
                        nullable = true,
                        description = "强平价（decimal string，PERP 逐仓,SPOT null）",
                        example = "37105.00")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal liquidationPrice,
        @Schema(
                        type = "string",
                        nullable = true,
                        description = "维持保证金（decimal string，PERP,SPOT null）",
                        example = "2.05")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal maintMargin,
        @Schema(type = "string", description = "per-position 累积保证金（decimal string，PERP,SPOT 0）", example = "40.00")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal frozenAmount,
        @Schema(
                        type = "string",
                        description =
                                "该 symbol 累计资金费率结算金额（decimal string，USDT,正=已收负=已付,SPOT 0;双向持仓 LONG+SHORT 行均显示该 symbol 合计）",
                        example = "2.50")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal cumulativeFunding,
        @Schema(description = "最后更新时间", example = "2026-07-04T12:00:05Z") Instant updatedAt) {}
