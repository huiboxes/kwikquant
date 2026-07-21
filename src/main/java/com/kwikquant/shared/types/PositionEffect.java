package com.kwikquant.shared.types;

/**
 * 合约下单方向(OKX 四向模型)。前端下单区 4 按钮直接映射,后端派生 side/reduceOnly/positionSide,
 * 各交易所差异在 CcxtOrderAdapter 层翻译。
 *
 * <ul>
 *   <li>{@code OPEN_LONG}   开多:side=BUY,  reduceOnly=false, positionSide=LONG</li>
 *   <li>{@code OPEN_SHORT}  开空:side=SELL, reduceOnly=false, positionSide=SHORT</li>
 *   <li>{@code CLOSE_LONG}  平多:side=SELL, reduceOnly=true(自动派生), positionSide=LONG</li>
 *   <li>{@code CLOSE_SHORT} 平空:side=BUY,  reduceOnly=true(自动派生), positionSide=SHORT</li>
 * </ul>
 *
 * <p>SPOT 订单不设(NULL),沿用 side BUY/SELL + 现货持仓逻辑。
 *
 * <p>reduceOnly 策略:平仓(CLOSE_*)自动 reduceOnly=true,前端不显式传(用户选"平仓自动 reduceOnly")。
 */
public enum PositionEffect {
    OPEN_LONG,
    OPEN_SHORT,
    CLOSE_LONG,
    CLOSE_SHORT,
}
