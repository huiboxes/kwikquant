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
                38,
                count,
                "Expected 38 ErrorCode constants (9 base + 6 trading 41xx + 2 risk 20xx + 6 strategy 70xx + 1 backtest 71xx + 1 worker 72xx + 5 wave8 73xx + 2 AI 80xx + 3 report 90xx + 3 mcp 10xxx; 上线前清理删 18 死常量:RISK_REJECTED/INSUFFICIENT_MARGIN/IDEMPOTENCY_CONFLICT/RATE_LIMITED/SERVICE_OVERLOADED/ORDER_NOT_FOUND/ORDER_EXCHANGE_REJECTED/ORDER_EXCHANGE_API_ERROR/STRATEGY_ALREADY_DELETED/BACKTEST_ALREADY_RUNNING/BACKTEST_SUBMISSION_FAILED/WORKER_NOT_RUNNING/WORKER_HEALTH_CHECK_FAILED/BACKTEST_RUNNER_FAILED/LLM_STREAM_INTERRUPTED/LLM_CONTEXT_TOO_LONG/REPORT_COMPARISON_INSUFFICIENT/MCP_BACKTEST_TIMEOUT)");
    }

    @Test
    void strategyNotEditable_hasCode7007() {
        assertEquals(7007, ErrorCode.STRATEGY_NOT_EDITABLE);
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
