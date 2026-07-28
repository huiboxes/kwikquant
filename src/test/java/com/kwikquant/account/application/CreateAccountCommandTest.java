package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.Exchange;
import org.junit.jupiter.api.Test;

/**
 * CreateAccountCommand 单测:toString() 屏蔽 apiKey/apiSecret/passphrase(安全关键——防日志/异常堆栈
 * 泄漏交易所凭证)。
 */
class CreateAccountCommandTest {

    @Test
    void toString_doesNotLeakSensitiveFields() {
        CreateAccountCommand cmd = new CreateAccountCommand(
                42L, Exchange.BINANCE, "main", "ak-key-123", "sk-secret-456", "pass-789", false, false);

        String s = cmd.toString();

        assertThat(s).doesNotContain("ak-key-123", "sk-secret-456", "pass-789");
    }

    @Test
    void toString_includesNonSensitiveFields() {
        CreateAccountCommand cmd =
                new CreateAccountCommand(42L, Exchange.OKX, "sub-account", "ak", "sk", "pp", true, true);

        String s = cmd.toString();

        assertThat(s).contains("userId=42");
        assertThat(s).contains("exchange=OKX");
        assertThat(s).contains("label=sub-account");
        assertThat(s).contains("paperTrading=true");
        assertThat(s).contains("testnet=true");
    }
}
