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
    CLOSE_SHORT;

    /**
     * 派生 positionSide 字符串(对齐 DB chk_positions_position_side 约束 'LONG'/'SHORT')。
     * OPEN_LONG/CLOSE_LONG → LONG,OPEN_SHORT/CLOSE_SHORT → SHORT。
     */
    public String toPositionSide() {
        // exhaustive switch:新增枚举值编译期强制分派,三目写法会静默落默认分支
        return switch (this) {
            case OPEN_LONG, CLOSE_LONG -> "LONG";
            case OPEN_SHORT, CLOSE_SHORT -> "SHORT";
        };
    }

    /**
     * 派生订单 side(类头四向映射表是唯一真相源):OPEN_LONG/CLOSE_SHORT → BUY,
     * OPEN_SHORT/CLOSE_LONG → SELL。PERP 订单的 side 由本方法派生,API 显式传入的 side
     * 必须与派生值一致(Order.validate 四象限校验),消灭 side/effect 双源矛盾输入。
     */
    public OrderSide toSide() {
        // exhaustive switch:同 toPositionSide,新增枚举值编译期强制分派
        return switch (this) {
            case OPEN_LONG, CLOSE_SHORT -> OrderSide.BUY;
            case OPEN_SHORT, CLOSE_LONG -> OrderSide.SELL;
        };
    }
}
