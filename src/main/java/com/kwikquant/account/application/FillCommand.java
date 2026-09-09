package com.kwikquant.account.application;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import java.math.BigDecimal;

/**
 * 成交命令:把一次 fill 应用到余额服务。
 *
 * <p>SPOT 场景 {@code marketType}/{@code positionEffect} 为 null,沿用既有 SPOT 余额逻辑。
 * PERP 场景 BalanceService/PaperBalanceAdapter 会按 {@code marketType} 分支处理保证金,
 * 按 {@code positionEffect} 区分开仓(OPEN_*)/平仓(CLOSE_*)资金流,按 {@code marginMode}
 * 区分 ISOLATED(保证金转锁定,留在 used)与 CROSS(账户担保不划转,估算释放回 free)。
 *
 * <p>{@code marginDelta} 是持仓内核算出的本次仓位保证金变动(OPEN = +initialMargin 增量,
 * CLOSE = −释放量;来源 {@code PositionService.PerpFillOutcome}),ISOLATED 分支按它锁定/
 * 释放精确金额,保证 paper_balances.used 与 positions.frozen_amount 两本账逐笔一致;null
 * 时 ISOLATED OPEN 退化为按冻结估算额锁定(兼容历史调用点)。
 *
 * <p>字段顺序保留旧序,新字段加末尾,避免破坏既有调用点位置参数(只需在末尾补 null)。
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
        PositionEffect positionEffect,
        MarginMode marginMode,
        BigDecimal marginDelta) {

    public FillCommand {
        // SPOT 场景 marketType/positionEffect/marginMode/marginDelta 允许 null;
        // PERP 场景由下游 BalanceService 校验非 null,此处不做校验以保持纯加法。
    }
}
