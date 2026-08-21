package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.trading.application.PositionEnrichment;
import com.kwikquant.trading.domain.Position;
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
        String qty,
        String avgEntryPrice,
        String realizedPnl,
        String unrealizedPnl,
        String currentPrice,
        Integer leverage,
        String marginMode,
        String positionSide,
        String liquidationPrice,
        String maintMargin,
        String frozenAmount,
        String cumulativeFunding,
        long version,
        Instant updatedAt) {

    public static PositionView from(Position p, PositionEnrichment e) {
        return new PositionView(
                p.getId(),
                p.getAccountId(),
                p.getSymbol(),
                p.getSide(),
                str(p.getQty()),
                str(p.getAvgEntryPrice()),
                str(p.getRealizedPnl()),
                str(e.unrealizedPnl()),
                str(e.currentPrice()),
                p.getLeverage(),
                p.getMarginMode() != null ? p.getMarginMode().name() : null,
                p.getPositionSide(),
                str(p.getLiquidationPrice()),
                str(p.getMaintMargin()),
                str(p.getFrozenAmount()),
                str(e.cumulativeFunding()),
                p.getVersion(),
                p.getUpdatedAt());
    }

    /** 金额红线:MCP 通道金额一律字符串输出(toPlainString 保精度),null 透传 null。 */
    private static String str(java.math.BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
