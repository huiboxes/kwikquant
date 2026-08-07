package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.trading.domain.Fill;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP {@code get_liquidation_history} 工具返回的强平明细投影。强平 Fill(external_fill_id "liq-" 前缀,
 * 由 {@code LiquidationService.processLiquidation} 创建)含强平价(=markPrice)/数量/已实现 PnL。
 */
public record LiquidationView(
        Long fillId,
        long orderId,
        long accountId,
        String symbol,
        String side,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal fee,
        String feeCurrency,
        String liquidity,
        String externalFillId,
        BigDecimal realizedPnl,
        Instant filledAt) {

    public static LiquidationView from(Fill f) {
        return new LiquidationView(
                f.getId(),
                f.getOrderId(),
                f.getAccountId(),
                f.getSymbol(),
                f.getSide() != null ? f.getSide().name() : null,
                f.getPrice(),
                f.getQty(),
                f.getFee(),
                f.getFeeCurrency(),
                f.getLiquidity(),
                f.getExternalFillId(),
                f.getRealizedPnlDelta(),
                f.getFilledAt());
    }
}
