package com.kwikquant.shared.types;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LiquidationEventTest {

    @Test
    void recordEquality_withOrderId() {
        Instant ts = Instant.now();
        LiquidationEvent a = new LiquidationEvent(
                1L,
                100L,
                10L,
                200L,
                "LONG",
                10,
                new BigDecimal("38010"),
                new BigDecimal("38500"),
                new BigDecimal("1500"),
                new BigDecimal("-500"),
                "mark price hit liquidation",
                ts);
        LiquidationEvent b = new LiquidationEvent(
                1L,
                100L,
                10L,
                200L,
                "LONG",
                10,
                new BigDecimal("38010"),
                new BigDecimal("38500"),
                new BigDecimal("1500"),
                new BigDecimal("-500"),
                "mark price hit liquidation",
                ts);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).contains("LiquidationEvent");
    }

    @Test
    void orderIdNullable_strongLiquidationNoTriggerOrder() {
        // 强平无触发订单:orderId 可空(§11 m1-new 拍板)
        LiquidationEvent e = new LiquidationEvent(
                1L,
                null,
                10L,
                200L,
                "SHORT",
                20,
                new BigDecimal("43890"),
                new BigDecimal("44000"),
                new BigDecimal("-200"),
                new BigDecimal("-300"),
                "mark price crossed liquidation",
                Instant.now());
        assertThat(e.orderId()).isNull();
        assertThat(e.userId()).isEqualTo(1L);
        assertThat(e.positionId()).isEqualTo(200L);
        assertThat(e.positionSide()).isEqualTo("SHORT");
        assertThat(e.leverage()).isEqualTo(20);
    }

    @Test
    void accessorsReturnExactValues() {
        Instant ts = Instant.parse("2026-07-21T00:00:00Z");
        LiquidationEvent e = new LiquidationEvent(
                7L,
                99L,
                11L,
                300L,
                "LONG",
                5,
                new BigDecimal("38000"),
                new BigDecimal("39000"),
                new BigDecimal("1200"),
                new BigDecimal("300"),
                "trigger",
                ts);
        assertThat(e.userId()).isEqualTo(7L);
        assertThat(e.orderId()).isEqualTo(99L);
        assertThat(e.accountId()).isEqualTo(11L);
        assertThat(e.positionId()).isEqualTo(300L);
        assertThat(e.liquidationPrice()).isEqualByComparingTo("38000");
        assertThat(e.markPrice()).isEqualByComparingTo("39000");
        assertThat(e.marginBalance()).isEqualByComparingTo("1200");
        assertThat(e.realizedPnl()).isEqualByComparingTo("300");
        assertThat(e.reason()).isEqualTo("trigger");
        assertThat(e.timestamp()).isEqualTo(ts);
    }

    @Test
    void nullPositionSideThrowsNpe() {
        assertThatThrownBy(() ->
                        new LiquidationEvent(1L, null, 10L, 200L, null, 10, null, null, null, null, "r", Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("positionSide");
    }

    @Test
    void nullReasonThrowsNpe() {
        assertThatThrownBy(() -> new LiquidationEvent(
                        1L, null, 10L, 200L, "LONG", 10, null, null, null, null, null, Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void nullTimestampThrowsNpe() {
        assertThatThrownBy(
                        () -> new LiquidationEvent(1L, null, 10L, 200L, "LONG", 10, null, null, null, null, "r", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }
}
