package com.kwikquant.trading.domain;

import java.math.BigDecimal;

/**
 * 撮合配置。MatchingKernel 纯函数的第三个参数（前两个：Order、MarketSnapshot）。
 *
 * @param fidelity 撮合保真度
 * @param marketSlippageBps 市价单滑点 basis points（10=0.1%）；FAST 模式才用
 * @param partialFillEnabled 是否模拟部分成交（v1 默认 false，依据 snap.volume 切分留待 v2）
 * @param makerFeeRate maker 手续费率（限价成交）
 * @param takerFeeRate taker 手续费率（市价 / 限价穿越）
 */
public record MatchConfig(
        MatchingFidelity fidelity,
        BigDecimal marketSlippageBps,
        boolean partialFillEnabled,
        BigDecimal makerFeeRate,
        BigDecimal takerFeeRate) {

    /** 默认市价单滑点（basis points，5=0.05%）。 */
    private static final BigDecimal DEFAULT_SLIPPAGE_BPS = new BigDecimal("5");

    /** 默认 maker 手续费率（限价成交）。 */
    private static final BigDecimal DEFAULT_MAKER_FEE_RATE = new BigDecimal("0.001");

    /** 默认 taker 手续费率（市价 / 限价穿越）。 */
    private static final BigDecimal DEFAULT_TAKER_FEE_RATE = new BigDecimal("0.002");

    public static MatchConfig defaults() {
        return new MatchConfig(
                MatchingFidelity.FAST, DEFAULT_SLIPPAGE_BPS, false, DEFAULT_MAKER_FEE_RATE, DEFAULT_TAKER_FEE_RATE);
    }

    public static MatchConfig spread() {
        return new MatchConfig(
                MatchingFidelity.SPREAD, DEFAULT_SLIPPAGE_BPS, false, DEFAULT_MAKER_FEE_RATE, DEFAULT_TAKER_FEE_RATE);
    }

    public static MatchConfig depth() {
        return new MatchConfig(
                MatchingFidelity.DEPTH, DEFAULT_SLIPPAGE_BPS, false, DEFAULT_MAKER_FEE_RATE, DEFAULT_TAKER_FEE_RATE);
    }
}
