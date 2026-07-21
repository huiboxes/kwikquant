package com.kwikquant.shared.types;

/**
 * 保证金模式(PERP 合约)。
 *
 * <ul>
 *   <li>{@code ISOLATED} 逐仓:每仓保证金独立,强平只损该仓。</li>
 *   <li>{@code CROSS} 全仓:账户内所有仓共享账户保证金,一仓爆连带拖累(实现留账,见 tech-design §8)。</li>
 * </ul>
 *
 * <p>SPOT 持仓/订单不设(NULL)。
 */
public enum MarginMode {
    ISOLATED,
    CROSS,
}
