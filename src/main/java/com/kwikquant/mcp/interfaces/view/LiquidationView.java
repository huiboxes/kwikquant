package com.kwikquant.mcp.interfaces.view;

import static com.kwikquant.mcp.interfaces.view.DecimalStrings.str;

import com.kwikquant.trading.domain.Fill;
import java.time.Instant;

/**
 * MCP {@code get_liquidation_history} 工具返回的强平明细投影。强平 Fill(external_fill_id "liq-" 前缀,
 * 由 {@code LiquidationService.processLiquidation} 创建)含强平价(=markPrice)/数量/已实现 PnL。
 * 金额红线：价格/数量/手续费/盈亏一律字符串输出。
 */
public record LiquidationView(
        Long fillId,
        long orderId,
        long accountId,
        String symbol,
        String side,
        String price,
        String qty,
        String fee,
        String feeCurrency,
        String liquidity,
        String externalFillId,
        String realizedPnl,
        Instant filledAt) {

    public static LiquidationView from(Fill f) {
        return new LiquidationView(
                f.getId(),
                f.getOrderId(),
                f.getAccountId(),
                f.getSymbol(),
                f.getSide() != null ? f.getSide().name() : null,
                str(f.getPrice()),
                str(f.getQty()),
                str(f.getFee()),
                f.getFeeCurrency(),
                f.getLiquidity(),
                f.getExternalFillId(),
                str(f.getRealizedPnlDelta()),
                f.getFilledAt());
    }
}
