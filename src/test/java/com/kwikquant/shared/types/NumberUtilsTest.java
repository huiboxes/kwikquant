package com.kwikquant.shared.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * NumberUtils 纯函数单测(#26 JaCoCo 预存债)。
 *
 * <p>覆盖 asBd 各类型分支 + NumberFormatException catch(Float NaN / String invalid)
 * + 非 Number/String 返 null + asLong。
 */
class NumberUtilsTest {

    @Test
    void asBd_null_returnsNull() {
        assertThat(NumberUtils.asBd(null)).isNull();
    }

    @Test
    void asBd_bigDecimal_returnsSame() {
        assertThat(NumberUtils.asBd(new BigDecimal("1.5"))).isEqualByComparingTo("1.5");
    }

    @Test
    void asBd_bigInteger_returnsDecimal() {
        assertThat(NumberUtils.asBd(new BigInteger("42"))).isEqualByComparingTo("42");
    }

    @Test
    void asBd_long_returnsDecimal() {
        assertThat(NumberUtils.asBd(42L)).isEqualByComparingTo("42");
    }

    @Test
    void asBd_integer_returnsDecimal() {
        assertThat(NumberUtils.asBd(7)).isEqualByComparingTo("7");
    }

    @Test
    void asBd_floatNaN_numberFormatException_returnsNull() {
        // Float.NaN.toString()="NaN" → new BigDecimal("NaN") throw → catch null(L19-20)
        assertThat(NumberUtils.asBd(Float.NaN)).isNull();
    }

    @Test
    void asBd_stringValid_returnsDecimal() {
        assertThat(NumberUtils.asBd("3.14")).isEqualByComparingTo("3.14");
    }

    @Test
    void asBd_stringInvalid_numberFormatException_returnsNull() {
        // new BigDecimal("abc") throw → catch null(L26-27)
        assertThat(NumberUtils.asBd("abc")).isNull();
    }

    @Test
    void asBd_otherType_returnsNull() {
        // 非 Number/String(Boolean) → L30 return null
        assertThat(NumberUtils.asBd(Boolean.TRUE)).isNull();
    }

    @Test
    void asLong_number_returnsLongValue() {
        assertThat(NumberUtils.asLong(42L)).isEqualTo(42L);
        assertThat(NumberUtils.asLong(7)).isEqualTo(7L);
    }

    @Test
    void asLong_nonNumber_returnsNull() {
        assertThat(NumberUtils.asLong("x")).isNull();
        assertThat(NumberUtils.asLong(null)).isNull();
    }
}
