package com.kwikquant.trading.application;

import java.math.BigDecimal;

/** 按账户汇总成交量和手续费的查询结果。从 FillMapper 提取到 application 层，避免跨模块依赖 infrastructure。 */
public record VolumeAndFees(BigDecimal totalVolume, BigDecimal totalFees) {}
