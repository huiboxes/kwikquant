package com.kwikquant.mcp.interfaces.view;

import java.math.BigDecimal;

/**
 * MCP 通道金额/价格/数量/比率一律字符串输出:JSON number 经 double 会丢精度,
 * {@link BigDecimal#toPlainString()} 保精度且避免科学计数法。全部 view 与工具层 Preview 构造
 * 共用这一处转换,保证各工具输出一致;null 透传 null(缺数据 ≠ 0)。
 */
public final class DecimalStrings {

    private DecimalStrings() {}

    public static String str(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
