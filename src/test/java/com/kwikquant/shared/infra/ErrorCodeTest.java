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
                52,
                count,
                "Expected 52 ErrorCode constants (13 base + 9 trading 41xx + 2 risk 20xx + 6 strategy 70xx + 3 backtest 71xx + 3 worker 72xx + 4 wave8 73xx + 4 AI 80xx + 4 report 90xx + 4 mcp 10xxx; 8001 LLM_KEY_NOT_FOUND 删除——走 4001/4003)");
    }
}
