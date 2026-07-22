package com.kwikquant.trading.domain;

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
    SHORT,
}
