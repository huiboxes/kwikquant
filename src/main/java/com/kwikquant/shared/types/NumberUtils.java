package com.kwikquant.shared.types;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class NumberUtils {

    private NumberUtils() {}

    public static BigDecimal asBd(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof BigInteger bi) return new BigDecimal(bi);
        if (o instanceof Long l) return BigDecimal.valueOf(l);
        if (o instanceof Integer i) return BigDecimal.valueOf(i);
        if (o instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (o instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 防御性 Object → Long 转换。非 Number 返回 null。 */
    public static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return null;
    }
}
