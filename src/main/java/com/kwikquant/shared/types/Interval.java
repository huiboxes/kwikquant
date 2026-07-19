package com.kwikquant.shared.types;

public enum Interval {
    _1m("1m"),
    _5m("5m"),
    _15m("15m"),
    _1h("1h"),
    _4h("4h"),
    _1d("1d");

    private final String ccxtValue;

    Interval(String ccxtValue) {
        this.ccxtValue = ccxtValue;
    }

    /** CCXT timeframe 字符串，如 "1m", "1h" */
    public String ccxtValue() {
        return ccxtValue;
    }

    /** 单根 K 线时长(毫秒),用于 before 分页算 CCXT since = before - limit*intervalMs(往前推 N 根)。 */
    public long toMillis() {
        return switch (this) {
            case _1m -> 60_000L;
            case _5m -> 300_000L;
            case _15m -> 900_000L;
            case _1h -> 3_600_000L;
            case _4h -> 14_400_000L;
            case _1d -> 86_400_000L;
        };
    }

    /** 从 CCXT timeframe 字符串解析。找不到则抛 IllegalArgumentException */
    public static Interval fromCcxt(String ccxt) {
        for (Interval i : values()) {
            if (i.ccxtValue.equals(ccxt)) return i;
        }
        throw new IllegalArgumentException("unsupported interval: " + ccxt);
    }
}
