package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.StrategyDefinition;

/**
 * fork 结果。首回测是 best-effort：{@code firstBacktestTaskId} 非空 = 已提交；
 * 为空时 {@code backtestSkipReason} 给出用户可读原因（配额满 / worker 不可用 / 其他），
 * fork 本身始终成功——策略与已发布代码已落库，用户可稍后在策略工作台手动回测。
 */
public record TemplateForkResult(StrategyDefinition strategy, Long firstBacktestTaskId, String backtestSkipReason) {}
