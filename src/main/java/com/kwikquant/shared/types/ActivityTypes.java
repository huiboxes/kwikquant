package com.kwikquant.shared.types;

import java.util.List;

/** 活动流事件类型常量。Listener 发布和 ActivityFeedService 查询共用此列表。 */
public final class ActivityTypes {

    public static final String ORDER_FILLED = "ORDER_FILLED";
    public static final String RISK_TRIGGERED = "RISK_TRIGGERED";
    public static final String STRATEGY_STATE_CHANGED = "STRATEGY_STATE_CHANGED";

    /** 所有活动类型的不可变列表（用于 SQL IN 子句构建和校验）。 */
    public static final List<String> ALL = List.of(ORDER_FILLED, RISK_TRIGGERED, STRATEGY_STATE_CHANGED);

    private ActivityTypes() {}
}
