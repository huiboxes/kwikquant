package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void allErrorCodeValuesMustBeUnique() throws Exception {
        Set<Integer> seen = new HashSet<>();
        int count = 0;
        for (Field field : ErrorCode.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())
                    && field.getType() == int.class) {
                int value = field.getInt(null);
                assertTrue(seen.add(value), "Duplicate ErrorCode value " + value + " on field " + field.getName());
                count++;
            }
        }
        assertEquals(
                55,
                count,
                "Expected 55 ErrorCode constants (14 base + 9 trading 41xx + 2 risk 20xx + 6 strategy 70xx + 3 backtest 71xx + 3 worker 72xx + 6 wave8 73xx + 4 AI 80xx + 4 report 90xx + 4 mcp 10xxx; 8001 LLM_KEY_NOT_FOUND 删除——走 4001/4003; 3002 INVITE_CODE_INVALID 注册门禁; 7305 BACKTEST_UNSUPPORTED_MARKET_TYPE 阶段2g 新增)");
    }

    @Test
    void backtestUnsupportedMarketType_hasCode7305() {
        assertEquals(7305, ErrorCode.BACKTEST_UNSUPPORTED_MARKET_TYPE);
    }

    @Test
    void backtestNoMarketData_hasCode7304() {
        assertEquals(7304, ErrorCode.BACKTEST_NO_MARKET_DATA);
    }
}
