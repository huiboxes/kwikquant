package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionEventTest {
    @Test
    void of_projectsFromPositionDto() {
        Instant now = Instant.now();
        PositionDto dto = new PositionDto(
                1L,
                42L,
                "BTC/USDT",
                "long",
                new BigDecimal("0.5"),
                new BigDecimal("40000"),
                new BigDecimal("100"),
                new BigDecimal("50"),
                new BigDecimal("40100"),
                3L,
                null,
                null,
                null,
                null,
                null,
                null,
                now);
        PositionEvent event = PositionEvent.of(dto);
        assertThat(event.eventType()).isEqualTo("POSITION_UPDATED");
        assertThat(event.positionId()).isEqualTo(1L);
        assertThat(event.accountId()).isEqualTo(42L);
        assertThat(event.symbol()).isEqualTo("BTC/USDT");
        assertThat(event.side()).isEqualTo("long");
        assertThat(event.qty()).isEqualByComparingTo("0.5");
        assertThat(event.avgEntryPrice()).isEqualByComparingTo("40000");
        assertThat(event.realizedPnl()).isEqualByComparingTo("100");
        assertThat(event.version()).isEqualTo(3L);
        assertThat(event.updatedAt()).isEqualTo(now);
    }

    @Test
    void recordEquality() {
        Instant t = Instant.parse("2026-07-01T00:00:00Z");
        PositionEvent a = new PositionEvent(
                "POSITION_UPDATED",
                1L,
                42L,
                "BTC/USDT",
                "long",
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                1L,
                t);
        PositionEvent b = new PositionEvent(
                "POSITION_UPDATED",
                1L,
                42L,
                "BTC/USDT",
                "long",
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                1L,
                t);
        assertThat(a).isEqualTo(b);
    }
}
