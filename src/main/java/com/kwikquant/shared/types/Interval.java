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

    /** 从 CCXT timeframe 字符串解析。找不到则抛 IllegalArgumentException */
    public static Interval fromCcxt(String ccxt) {
        for (Interval i : values()) {
            if (i.ccxtValue.equals(ccxt)) return i;
        }
        throw new IllegalArgumentException("unsupported interval: " + ccxt);
    }
}
