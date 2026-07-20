package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteCurrencyPropertiesTest {

    @Test
    void defaults_whenNull_appliesUsdtAnd100000() {
        QuoteCurrencyProperties p = new QuoteCurrencyProperties(null, null);
        assertThat(p.getAllowedCurrencies()).containsExactly("USDT");
        assertThat(p.getPaperInitialBalance()).isEqualByComparingTo("100000");
        assertThat(p.primaryQuoteCurrency()).isEqualTo("USDT");
    }

    @Test
    void custom_whenProvided_keepsValues() {
        QuoteCurrencyProperties p =
                new QuoteCurrencyProperties(List.of("USDC", "USDT"), new BigDecimal("50000"));
        assertThat(p.getAllowedCurrencies()).containsExactly("USDC", "USDT");
        assertThat(p.getPaperInitialBalance()).isEqualByComparingTo("50000");
        assertThat(p.primaryQuoteCurrency()).isEqualTo("USDC"); // get(0) 主 quote
    }

    @Test
    void emptyList_fallsBackToUsdtDefault() {
        QuoteCurrencyProperties p = new QuoteCurrencyProperties(List.of(), new BigDecimal("1000"));
        assertThat(p.getAllowedCurrencies()).containsExactly("USDT");
        assertThat(p.primaryQuoteCurrency()).isEqualTo("USDT");
    }
}
