package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.trading.application.PositionEnrichment;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP 持仓工具返回投影。对齐 REST {@code PositionDto} 口径(合约字段 + 行情富化),剥掉 {@code @Schema}。
 * 组装(当前市价 / 未实现盈亏 / 累计资金费)由 {@code PositionEnricher} 统一产出 {@link PositionEnrichment},
 * REST 与 MCP 共用(DRY,避免两处行情拉取 + 资金费聚合漂移)。
 */
public record PositionView(
        Long positionId,
        long accountId,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal avgEntryPrice,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal currentPrice,
        Integer leverage,
        String marginMode,
        String positionSide,
        BigDecimal liquidationPrice,
        BigDecimal maintMargin,
        BigDecimal frozenAmount,
        BigDecimal cumulativeFunding,
        long version,
        Instant updatedAt) {

    public static PositionView from(Position p, PositionEnrichment e) {
        return new PositionView(
                p.getId(),
                p.getAccountId(),
                p.getSymbol(),
                p.getSide(),
                p.getQty(),
                p.getAvgEntryPrice(),
                p.getRealizedPnl(),
                e.unrealizedPnl(),
                e.currentPrice(),
                p.getLeverage(),
                p.getMarginMode() != null ? p.getMarginMode().name() : null,
                p.getPositionSide(),
                p.getLiquidationPrice(),
                p.getMaintMargin(),
                p.getFrozenAmount(),
                e.cumulativeFunding(),
                p.getVersion(),
                p.getUpdatedAt());
    }
}
