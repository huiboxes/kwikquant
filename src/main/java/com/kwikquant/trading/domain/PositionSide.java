package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.PositionEffect;

/**
 * 合约持仓方向(PERP 双向持仓)。
 *
 * <p>SPOT 持仓不设(null)。与 {@code Position.positionSide} 字符串字段("LONG"/"SHORT")语义等价;
 * 字段仍为 String 便于 DB 直接存(§10 M14),adapter 契约层用本 enum 强类型。
 *
 * <p>本 enum 仅用于 CcxtOrderAdapter 契约(setLeverage/setMarginMode/PositionSnapshot);
 * Position 聚合根的 positionSide 字段仍为 String,由 4a.3/4a.5 在调用 adapter 时转换。
 */
public enum PositionSide {
    LONG,
    SHORT;

    /**
     * 从 {@link PositionEffect} 派生持仓方向(4a.5):OPEN_LONG/CLOSE_LONG → LONG,OPEN_SHORT/CLOSE_SHORT → SHORT。
     *
     * <p>通用派生逻辑(非 OKX 专属),LiveExecutor 缓存 key + adapter posSide 参数共用,避免两处重复派生。
     * SPOT(positionEffect=null)返 null。
     */
    public static PositionSide from(PositionEffect effect) {
        if (effect == null) {
            return null;
        }
        return switch (effect) {
            case OPEN_LONG, CLOSE_LONG -> LONG;
            case OPEN_SHORT, CLOSE_SHORT -> SHORT;
        };
    }
}
