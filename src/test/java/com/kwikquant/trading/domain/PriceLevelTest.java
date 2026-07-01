package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.PriceLevel;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceLevelTest {

    @Test
    void recordExposesFields() {
        PriceLevel l = new PriceLevel(new BigDecimal("42000"), new BigDecimal("0.5"));
        assertThat(l.price()).isEqualByComparingTo("42000");
        assertThat(l.qty()).isEqualByComparingTo("0.5");
    }
}
