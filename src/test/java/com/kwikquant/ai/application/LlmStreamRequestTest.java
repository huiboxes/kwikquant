package com.kwikquant.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LlmStreamRequest 单测:toString() 屏蔽 apiSecret(安全关键——防日志/异常路径泄漏解密后的完整 API key)。
 */
class LlmStreamRequestTest {

    @Test
    void toString_doesNotLeakApiSecret() {
        LlmStreamRequest req =
                new LlmStreamRequest("sk-secret-123", "https://api.example.com", "gpt-4o", List.of(), 0.7, 4096);

        String s = req.toString();

        assertThat(s).contains("***REDACTED***");
        assertThat(s).doesNotContain("sk-secret-123");
    }

    @Test
    void toString_includesDiagnosticFields() {
        LlmStreamRequest req = new LlmStreamRequest("sk-x", "https://api.example.com", "gpt-4o", List.of(), 0.7, 4096);

        String s = req.toString();

        assertThat(s).contains("baseUrl=https://api.example.com");
        assertThat(s).contains("model=gpt-4o");
        assertThat(s).contains("temperature=0.7");
        assertThat(s).contains("maxTokens=4096");
        assertThat(s).contains("messages=0 msgs");
    }

    @Test
    void toString_nullMessages_showsZeroCount() {
        LlmStreamRequest req = new LlmStreamRequest("sk-x", null, null, null, 0.0, 0);

        String s = req.toString();

        // messages==null → 三元分支返 0
        assertThat(s).contains("messages=0 msgs");
        assertThat(s).contains("model=null");
    }
}
