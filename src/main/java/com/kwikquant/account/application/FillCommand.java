package com.kwikquant.account.application;

import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;

/**
 * 成交命令:把一次 fill 应用到余额服务。
 *
 * <p>SPOT 场景 {@code marketType}/{@code positionEffect} 为 null,沿用既有 SPOT 余额逻辑。
 * PERP 场景(阶段2)BalanceService/PaperBalanceAdapter 会按 {@code marketType} 分支处理保证金,
 * 按 {@code positionEffect} 区分开仓(CLOSE_* 反向)/平仓(OPEN_* 正向)资金流。
 *
 * <p>字段顺序保留旧序,新字段加末尾,避免破坏既有调用点位置参数(只需在末尾补 null, null)。
 */
public record FillCommand(
        long accountId,
        boolean paperTrading,
        OrderSide side,
        String symbol,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal frozenQuoteAmount,
        MarketType marketType,
        PositionEffect positionEffect) {

    public FillCommand {
        // SPOT 场景 marketType/positionEffect 允许 null;
        // PERP 场景由下游 BalanceService 校验非 null,此处不做校验以保持纯加法。
    }
}
