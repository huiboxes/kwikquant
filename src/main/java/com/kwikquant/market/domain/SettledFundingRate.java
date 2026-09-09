package com.kwikquant.market.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 已结算资金费期次(回测资金费回放专用视图,docs/perp-backtest-spec.md §5)。
 *
 * <p>与 {@link FundingRatePeriod} 的差异:只含已结算行(settledRate 恒非空),不带 predictedRate,
 * 额外带 {@code source}——跨所代理期次(source=PROXY_BINANCE)必须在回测报告 warnings 显性
 * 标注基差风险,消费方(worker)按 source 统计。
 *
 * <p>金额通道纪律(与 runner REST 同款):settledRate/markPrice 以 decimal string 序列化,
 * Python 侧 {@code Decimal(str)} 直读不经 JSON number/float 中转(资金费费率与强平极值
 * 全压在这条链上,16+ 位有效数字场景 float 会静默丢末位)。
 *
 * @param fundingTime     期次键(结算时刻,交易所网格)
 * @param settledRate     已结算费率(正=多头付)
 * @param intervalSeconds 期次周期(1h/4h/8h,回放不硬编码周期)
 * @param markPrice       结算时标记价(best-effort,可空;回测用 bar.close 代理,不消费此值,透传供诊断)
 * @param source          数据来源(EXCHANGE=本所 / PROXY_BINANCE=跨所代理)
 */
public record SettledFundingRate(
        Instant fundingTime,
        @Schema(type = "string", description = "已结算费率(decimal string,正=多头付)", example = "0.0001")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal settledRate,
        Integer intervalSeconds,
        @Schema(type = "string", nullable = true, description = "结算时标记价(decimal string,best-effort 可空)")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal markPrice,
        String source) {}
