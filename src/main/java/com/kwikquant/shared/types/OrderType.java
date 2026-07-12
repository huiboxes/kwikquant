package com.kwikquant.shared.types;

public enum OrderType {
    MARKET,
    LIMIT,
    STOP_MARKET,
    STOP_LIMIT,
    TAKE_PROFIT_MARKET,
    TAKE_PROFIT_LIMIT,
    TRAILING_STOP;

    /** 条件单类型：需要触发条件才能撮合，当前系统无 strategy 组件无法触发。 */
    public boolean isConditional() {
        return switch (this) {
            case STOP_MARKET, STOP_LIMIT, TAKE_PROFIT_MARKET, TAKE_PROFIT_LIMIT, TRAILING_STOP -> true;
            case MARKET, LIMIT -> false;
        };
    }
}
