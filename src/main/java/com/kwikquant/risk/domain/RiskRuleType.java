package com.kwikquant.risk.domain;

/**
 * Risk rule types supported by the risk engine.
 *
 * <p>阶段2h(§10 M8/§11 M8-new)加 {@link #MAX_INITIAL_MARGIN}:PERP 初始保证金占用规则
 * (initialMargin = notional / leverage &lt;= availableMargin × ratio)。与 {@link #MAX_NOTIONAL}
 * 互补——MAX_NOTIONAL 对 PERP 跳过(高杠杆系统性拒单),PERP 走本规则。
 */
public enum RiskRuleType {
    MAX_NOTIONAL,
    DAILY_LOSS_LIMIT,
    ORDER_FREQUENCY,
    /** PERP 初始保证金占用规则(阶段2h §10 M8/§11 M8-new)。 */
    MAX_INITIAL_MARGIN,
}
