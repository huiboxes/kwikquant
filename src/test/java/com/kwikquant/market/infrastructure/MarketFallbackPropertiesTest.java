package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link MarketFallbackProperties} 默认值与下界钳制。 */
class MarketFallbackPropertiesTest {

    @Test
    void nullValues_fallBackToDefaults() {
        var p = new MarketFallbackProperties(null, null, null);
        assertThat(p.restPollInterval()).isEqualTo(MarketFallbackProperties.DEFAULT_REST_POLL_INTERVAL);
        assertThat(p.wsFallbackAfterFailures()).isEqualTo(MarketFallbackProperties.DEFAULT_WS_FALLBACK_AFTER_FAILURES);
        assertThat(p.wsRetryInterval()).isEqualTo(MarketFallbackProperties.DEFAULT_WS_RETRY_INTERVAL);
    }

    @Test
    void providedValues_kept() {
        var p = new MarketFallbackProperties(Duration.ofSeconds(10), 5, Duration.ofSeconds(120));
        assertThat(p.restPollInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.wsFallbackAfterFailures()).isEqualTo(5);
        assertThat(p.wsRetryInterval()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void negativeOrZeroValues_clampedToDefaults() {
        // 误配负/零值会让 worker sleep 抛错死亡或紧循环 → 回退默认
        var p = new MarketFallbackProperties(Duration.ofSeconds(-5), 0, Duration.ZERO);
        assertThat(p.restPollInterval()).isEqualTo(MarketFallbackProperties.DEFAULT_REST_POLL_INTERVAL);
        assertThat(p.wsFallbackAfterFailures()).isEqualTo(MarketFallbackProperties.DEFAULT_WS_FALLBACK_AFTER_FAILURES);
        assertThat(p.wsRetryInterval()).isEqualTo(MarketFallbackProperties.DEFAULT_WS_RETRY_INTERVAL);
    }
}
