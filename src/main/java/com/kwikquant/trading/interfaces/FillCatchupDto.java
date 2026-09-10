package com.kwikquant.trading.interfaces;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kwikquant.trading.application.FillCatchupRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 断线补拉成交行 DTO（GET /api/v1/worker/fills-since）。
 *
 * <p>键名与 WS FillEvent 载荷对齐（docs/ws-contract.md §3.4），worker 侧补拉行与 WS 事件
 * 走同一条派发过滤链；金额按 runner REST 通道惯例 decimal string（{@code @JsonFormat(STRING)}，
 * Python 侧 {@code Decimal(str)} 直读不绕 float——WS 载荷金额仍是 number，为已知契约缺口）。
 */
public record FillCatchupDto(
        @Schema(description = "成交 ID（补拉游标与去重键）", example = "1024") Long fillId,
        @Schema(description = "订单 ID", example = "42") Long orderId,
        @Schema(description = "账户 ID（恒为 token 绑定账户）", example = "7") Long accountId,
        @Schema(description = "canonical symbol", example = "BTC/USDT") String symbol,
        @Schema(description = "方向（小写: buy | sell）", example = "buy") String side,
        @Schema(type = "string", description = "成交价格（decimal string，精度 8 位）", example = "42150.50")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal price,
        @Schema(type = "string", description = "成交数量（decimal string，精度 8 位）", example = "0.0025")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal qty,
        @Schema(type = "string", description = "手续费（decimal string，精度 8 位）", example = "0.0052")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal fee,
        @Schema(description = "手续费币种", example = "USDT") String feeCurrency,
        @Schema(description = "流动性方向（枚举: taker | maker）", example = "taker") String liquidity,
        @Schema(
                        description = "持仓意图（枚举: OPEN_LONG | OPEN_SHORT | CLOSE_LONG | CLOSE_SHORT；SPOT/legacy 为 null）",
                        example = "CLOSE_LONG",
                        nullable = true)
                String positionEffect,
        @Schema(description = "市场类型（枚举: SPOT | PERP；存量 legacy 单为 null）", example = "PERP", nullable = true)
                String marketType,
        @Schema(description = "成交时间", example = "2026-07-04T12:00:05Z") Instant filledAt) {

    public static FillCatchupDto from(FillCatchupRow row) {
        return new FillCatchupDto(
                row.id(),
                row.orderId(),
                row.accountId(),
                row.symbol(),
                row.side() != null ? row.side().name().toLowerCase() : null,
                row.price(),
                row.qty(),
                row.fee(),
                row.feeCurrency(),
                row.liquidity(),
                row.positionEffect() != null ? row.positionEffect().name() : null,
                row.marketType() != null ? row.marketType().name() : null,
                row.filledAt());
    }
}
