package com.kwikquant.market.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * funding_rates 期序列表(V57)的一行:某交易对某个资金费期次的费率快照。
 *
 * <p>{@code fundingTime} 是期次键(结算时刻,交易所网格如 OKX 0/8/16 UTC);{@code settledRate}
 * 只在期次已结算后有值(COLESCE 保旧值,采集不冲掉),{@code predictedRate} 是结算前滚动采集的
 * 预估(结算后自然冻结);{@code markPrice} best-effort(OKX unified 无标记价时恒 null)。
 *
 * <p>{@code source} 标注费率来源(EXCHANGE=本所采集 / PROXY_BINANCE=跨所代理补写):paper
 * 结算侧对 PROXY 行留观测痕迹(audit metadata + warn),回测侧由 worker 统计进报告 warnings。
 *
 * <p>消费方:PAPER 资金费期次结算(catch-up 读 settled 行)、LIVE 账单富化(按期次键反查
 * 费率/间隔)、回测资金费回放(批次 E)。
 */
public record FundingRatePeriod(
        Instant fundingTime,
        BigDecimal settledRate,
        BigDecimal predictedRate,
        Integer intervalSeconds,
        BigDecimal markPrice,
        String source) {}
