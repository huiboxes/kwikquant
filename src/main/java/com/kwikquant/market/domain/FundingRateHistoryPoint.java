package com.kwikquant.market.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 交易所历史资金费率单点（CCXT {@code fetchFundingRateHistory} 元素），语义为<b>已结算</b>值。
 * {@code fundingTime} = 交易所原生结算时刻（期次键），不是本地墙钟。
 */
public record FundingRateHistoryPoint(String symbol, Instant fundingTime, BigDecimal rate) {

    public FundingRateHistoryPoint {
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(fundingTime);
        Objects.requireNonNull(rate);
    }
}
