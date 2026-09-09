package com.kwikquant.market.domain;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 资金费率快照（仅永续合约）。CCXT {@code fetchFundingRate} 返回的当前/下一轮费率及标记价。
 * 除 exchange/marketType/symbol/receivedAt 外字段均可空（交易所实现差异），由调用方按需 null 检查。
 *
 * <p>费率语义（OKX 2026-09 实测）：{@code fundingRate} 是<b>当期预估值</b>（累计中、指向未来的结算时刻
 * {@code fundingTime}）；<b>已结算值</b>只存在于 OKX 原始 payload 的 {@code info.settFundingRate}
 * （CCXT unified 不放进任何自有字段），由适配器提取为 {@code settledFundingRate}。两者可差数倍且相邻期
 * 符号会翻转——结算已结束的期次必须用已结算值 + 期次键，严禁拿预估值充数。
 */
public record FundingRate(
        Exchange exchange,
        MarketType marketType,
        String symbol,
        BigDecimal fundingRate,
        Instant fundingTime,
        Integer intervalSeconds,
        BigDecimal settledFundingRate,
        BigDecimal markPrice,
        BigDecimal nextFundingRate,
        Instant nextFundingTime,
        Instant timestamp,
        Instant receivedAt) {

    public FundingRate {
        Objects.requireNonNull(exchange);
        Objects.requireNonNull(marketType);
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(receivedAt);
    }

    /**
     * {@code settledFundingRate} 所属期次的结算时刻 = fundingTime − interval（交易所期次网格）。
     * 任一缺失返回 null——调用方不得在没有期次键的情况下落已结算值。
     */
    public Instant settledFundingTime() {
        if (fundingTime == null || intervalSeconds == null) return null;
        return fundingTime.minusSeconds(intervalSeconds);
    }
}
