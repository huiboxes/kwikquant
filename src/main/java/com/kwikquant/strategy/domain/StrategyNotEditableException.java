package com.kwikquant.strategy.domain;

import com.kwikquant.shared.types.StrategyStatus;

/**
 * 策略不可编辑/删除异常。状态不满足 update/delete 的前置可编辑性条件时抛出。
 *
 * <p>与 {@link IllegalStrategyStateTransitionException} 区别:后者表示状态机转换非法
 * (from→to),用于 lifecycle ready/start/stop/pause/restart;本异常表示"当前状态不允许
 * 该操作"(可编辑性门控,无目标状态),用于 {@code StrategyCrudService.update/delete}。
 * 映射到 {@code ErrorCode.STRATEGY_NOT_EDITABLE}(7007,409)。
 */
public class StrategyNotEditableException extends RuntimeException {
    private final StrategyStatus status;
    private final String operation;

    /**
     * @param status 当前策略状态
     * @param operation 操作名("编辑"/"删除");删除给出可行动建议(先停止)
     */
    public StrategyNotEditableException(StrategyStatus status, String operation) {
        super(buildMessage(status, operation));
        this.status = status;
        this.operation = operation;
    }

    private static String buildMessage(StrategyStatus status, String operation) {
        String hint = "删除".equals(operation) ? ",请先停止策略" : "";
        return "策略状态 " + status + " 不可" + operation + hint;
    }

    public StrategyStatus status() {
        return status;
    }

    public String operation() {
        return operation;
    }
}
