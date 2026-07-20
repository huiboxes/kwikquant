package com.kwikquant.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import org.junit.jupiter.api.Test;

class SymbolNotListedExceptionTest {

    @Test
    void getters_returnInjectedValues_andMessageMentionsAll() {
        var ex = new SymbolNotListedException(Exchange.OKX, MarketType.PERP, "DOGE/USDT");
        assertThat(ex.exchange()).isEqualTo(Exchange.OKX);
        assertThat(ex.marketType()).isEqualTo(MarketType.PERP);
        assertThat(ex.canonicalSymbol()).isEqualTo("DOGE/USDT");
        assertThat(ex.getMessage()).contains("DOGE/USDT").contains("OKX").contains("PERP");
    }
}
