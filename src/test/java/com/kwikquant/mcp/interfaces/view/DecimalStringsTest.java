package com.kwikquant.mcp.interfaces.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** {@link DecimalStrings} 金额红线统一转换:null 透传 + toPlainString 保精度去科学计数法。 */
class DecimalStringsTest {

    @Test
    void str_null_returnsNull() {
        assertThat(DecimalStrings.str(null)).isNull();
    }

    @Test
    void str_plainValue_keepsScale() {
        assertThat(DecimalStrings.str(new BigDecimal("100"))).isEqualTo("100");
        assertThat(DecimalStrings.str(new BigDecimal("0.5"))).isEqualTo("0.5");
        assertThat(DecimalStrings.str(new BigDecimal("-0.5"))).isEqualTo("-0.5");
        // scale 保留(0.6000 不归一成 0.6),不做数值归一化
        assertThat(DecimalStrings.str(new BigDecimal("0.6000"))).isEqualTo("0.6000");
    }

    @Test
    void str_exponentialForm_expandedToPlain() {
        // JSON number 精度红线的核心:科学计数法展开为普通十进制串
        assertThat(DecimalStrings.str(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(DecimalStrings.str(new BigDecimal("1.5E-7"))).isEqualTo("0.00000015");
        // valueOf(0.0001):Double.toString="1.0E-4" → scale 5,保 scale 输出尾零(不归一化)
        assertThat(DecimalStrings.str(BigDecimal.valueOf(0.0001))).isEqualTo("0.00010");
    }

    @Test
    void str_zero_returnsPlainZero() {
        assertThat(DecimalStrings.str(BigDecimal.ZERO)).isEqualTo("0");
    }
}
