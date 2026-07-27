package com.kwikquant.shared.types;

/**
 * 保证金模式(PERP 合约)。
 *
 * <ul>
 *   <li>{@code ISOLATED} 逐仓:每仓保证金独立,强平只损该仓。</li>
 *   <li>{@code CROSS} 全仓:账户内所有仓共享账户保证金,一仓爆连带拖累(当前实装仅 ISOLATED,CROSS 未实装,PositionService.applyPerpDelta 对两者同处理)。</li>
 * </ul>
 *
 * <p>SPOT 持仓/订单不设(NULL)。
 */
public enum MarginMode {
    ISOLATED,
    CROSS,
}
