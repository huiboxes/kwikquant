package com.kwikquant.trading.domain;

/**
 * Paper executor 撮合保真度。Backtest 永远是 FAST（仅有 K 线数据）。
 *
 * <ul>
 *   <li>FAST: last price + 固定 bps 滑点
 *   <li>SPREAD: bid/ask 价差（需要 ticker）
 *   <li>DEPTH: orderbook walk-the-book（需要 L2 深度）
 * </ul>
 */
public enum MatchingFidelity {
    FAST,
    SPREAD,
    DEPTH,
}
