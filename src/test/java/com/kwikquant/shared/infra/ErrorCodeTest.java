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
                42,
                count,
                "Expected 42 ErrorCode constants (10 base + 6 trading 41xx + 2 risk 20xx + 7 strategy 70xx + 1 backtest 71xx + 1 worker 72xx + 5 backtest-worker 73xx + 3 AI 80xx + 3 report 90xx + 4 mcp 10xxx);7302/7303 随撮合本地化删除,7305 为 backtest-worker-unavailable 复用,7307 系新增,1003 系认证限流新增,8004 系自然语言风控解析失败新增,7008 系策略模板不存在新增");
    }

    @Test
    void strategyNotEditable_hasCode7007() {
        assertEquals(7007, ErrorCode.STRATEGY_NOT_EDITABLE);
    }

    @Test
    void backtestNoMarketData_hasCode7304() {
        assertEquals(7304, ErrorCode.BACKTEST_NO_MARKET_DATA);
    }

    @Test
    void backtestQuotaExceeded_hasCode7306() {
        assertEquals(7306, ErrorCode.BACKTEST_QUOTA_EXCEEDED);
    }

    @Test
    void aiParseFailed_hasCode8004() {
        assertEquals(8004, ErrorCode.AI_PARSE_FAILED);
    }

    @Test
    void templateNotFound_hasCode7008() {
        assertEquals(7008, ErrorCode.TEMPLATE_NOT_FOUND);
    }

    @Test
    void mcpScopeDenied_hasCode10005() {
        assertEquals(10005, ErrorCode.MCP_SCOPE_DENIED);
    }

    @Test
    void mcpConfirmTokenInvalid_hasCode10006() {
        assertEquals(10006, ErrorCode.MCP_CONFIRM_TOKEN_INVALID);
    }
}
