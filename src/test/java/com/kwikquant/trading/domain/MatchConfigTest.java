package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MatchConfigTest {

    @Test
    void defaultsAreFast() {
        MatchConfig c = MatchConfig.defaults();
        assertThat(c.fidelity()).isEqualTo(MatchingFidelity.FAST);
        assertThat(c.marketSlippageBps()).isEqualByComparingTo("5");
        assertThat(c.partialFillEnabled()).isFalse();
        assertThat(c.makerFeeRate()).isEqualByComparingTo("0.001");
        assertThat(c.takerFeeRate()).isEqualByComparingTo("0.002");
    }

    @Test
    void spreadConfigSetsFidelity() {
        assertThat(MatchConfig.spread().fidelity()).isEqualTo(MatchingFidelity.SPREAD);
    }

    @Test
    void depthConfigSetsFidelity() {
        assertThat(MatchConfig.depth().fidelity()).isEqualTo(MatchingFidelity.DEPTH);
    }
}
