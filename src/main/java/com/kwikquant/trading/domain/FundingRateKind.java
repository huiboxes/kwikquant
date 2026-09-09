package com.kwikquant.trading.domain;

/**
 * 资金费率值类型(funding_settlements.rate_kind)。
 *
 * <p>区分"已结算值"与"预估值":OKX 预估值(CCXT {@code fetchFundingRate.fundingRate})指向
 * <b>下一个未结算期次</b>,与刚结算期的已结算值可差数倍、相邻期符号会翻转——拿预估值结算
 * 已结束的期次方向都可能算反。
 *
 * <ul>
 *   <li>{@link #SETTLED}:按交易所已结算值落账(LIVE bills 是交易所侧已发生事实;PAPER 读
 *       funding_rates.settled_rate 的期次结算)</li>
 *   <li>{@link #PREDICTED}:按预估值落账(期次化切换前的历史存量与过渡期降级路径)</li>
 * </ul>
 */
public enum FundingRateKind {
    SETTLED,
    PREDICTED
}
