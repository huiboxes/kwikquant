package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;

/**
 * MCP 持仓工具返回投影。{@code side} 透传 domain 的字符串值（{@code long}/{@code short}/{@code flat}），
 * 剥掉 version/createdAt 等内部字段。
 */
public record PositionView(
        long accountId, String symbol, String side, BigDecimal qty, BigDecimal avgEntryPrice, BigDecimal realizedPnl) {
    public static PositionView from(Position p) {
        return new PositionView(
                p.getAccountId(), p.getSymbol(), p.getSide(), p.getQty(), p.getAvgEntryPrice(), p.getRealizedPnl());
    }
}
