package com.kwikquant.report.domain;

import java.math.BigDecimal;

/**
 * 组合(多标的)回测的分标的终仓快照:回测区间结束时该标的的持仓数量与均价。
 *
 * <p>数量与均价均为金额/持仓精度,统一 {@link BigDecimal}。qty 为 0 表示该标的无持仓
 * (序列化时可省略,解析时容忍缺失)。
 *
 * @param symbol canonical 交易对,如 {@code BTC/USDT}
 * @param qty 持仓数量(基础币)
 * @param avgPrice 持仓均价(报价币)
 */
public record PositionSnapshot(String symbol, BigDecimal qty, BigDecimal avgPrice) {}
