package com.kwikquant.report.interfaces;

import java.math.BigDecimal;

public record TradeHistoryStatsDto(BigDecimal totalVolume, BigDecimal totalFees, BigDecimal realizedPnl) {}
