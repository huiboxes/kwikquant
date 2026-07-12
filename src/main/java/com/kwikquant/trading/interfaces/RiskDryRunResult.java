package com.kwikquant.trading.interfaces;

import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.risk.domain.RuleResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * 风控预检结果。回传 verdict 与计算所用的中间指标（透明化，让调用方感知 verdict 的输入依据，
 * 例如 MARKET BUY 行情 stale 导致 notional 为 null 的情形）。
 *
 * @param verdict          APPROVED | REJECTED
 * @param notionalValue    名义额（USDT），价格不可得时为 null
 * @param recentOrderCount 近 60s 下单数
 * @param dailyRealizedPnl 当日已实现盈亏
 * @param ruleResults      每条规则的评估结果
 */
public record RiskDryRunResult(
        @Schema(description = "风控判定 APPROVED | REJECTED") RiskVerdict verdict,
        @Schema(description = "名义额 USDT，价格不可得时为 null") BigDecimal notionalValue,
        @Schema(description = "近 60s 下单数") int recentOrderCount,
        @Schema(description = "当日已实现盈亏") BigDecimal dailyRealizedPnl,
        @Schema(description = "每条规则的评估结果") List<RuleResult> ruleResults) {}
