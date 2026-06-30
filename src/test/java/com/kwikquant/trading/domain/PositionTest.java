package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionTest {

    @Test
    void flatFactoryProducesFlatPosition() {
        Position p = Position.flat(1L, "BTC/USDT");
        assertThat(p.getAccountId()).isEqualTo(1L);
        assertThat(p.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(p.getSide()).isEqualTo(Position.SIDE_FLAT);
        assertThat(p.getQty()).isEqualByComparingTo("0");
        assertThat(p.getAvgEntryPrice()).isNull();
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("0");
        assertThat(p.getVersion()).isZero();
        assertThat(p.isFlat()).isTrue();
    }

    @Test
    void longPositionIsNotFlat() {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setQty(new BigDecimal("0.5"));
        assertThat(p.isFlat()).isFalse();
    }

    @Test
    void zeroQtyIsFlatEvenWithLongSide() {
        Position p = new Position();
        p.setSide(Position.SIDE_LONG);
        p.setQty(BigDecimal.ZERO);
        assertThat(p.isFlat()).isTrue();
    }

    @Test
    void settersAndGetters() {
        Position p = new Position();
        Instant now = Instant.now();
        p.setId(1L);
        p.setAvgEntryPrice(new BigDecimal("42000"));
        p.setRealizedPnl(new BigDecimal("100"));
        p.setVersion(5L);
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getAvgEntryPrice()).isEqualByComparingTo("42000");
        assertThat(p.getRealizedPnl()).isEqualByComparingTo("100");
        assertThat(p.getVersion()).isEqualTo(5L);
        assertThat(p.getCreatedAt()).isEqualTo(now);
        assertThat(p.getUpdatedAt()).isEqualTo(now);
    }
}
