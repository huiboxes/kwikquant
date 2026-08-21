package com.kwikquant.mcp.interfaces.view;

import java.util.List;

/** emergency_stop 两阶段确认预览:将被停止的 RUNNING 策略清单(确认时快照;执行时重新查询)。 */
public record EmergencyStopPreview(List<StrategyRef> runningStrategies) {
    /** 策略引用(id+name,供人类核对)。 */
    public record StrategyRef(Long id, String name) {}
}
