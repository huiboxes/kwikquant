package com.kwikquant.trading.domain;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FillTest {

    @Test
    void createPopulatesFields() {
        Instant t = Instant.parse("2026-06-30T00:00:00Z");
        Fill f = Fill.create(
                1L,
                2L,
                "BTC/USDT",
                OrderSide.BUY,
                new BigDecimal("42000"),
                new BigDecimal("0.1"),
                new BigDecimal("4.2"),
                "USDT",
                "maker",
                "ext-1",
                t);
        assertThat(f.getId()).isNull();
        assertThat(f.getOrderId()).isEqualTo(1L);
        assertThat(f.getAccountId()).isEqualTo(2L);
        assertThat(f.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(f.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(f.getPrice()).isEqualByComparingTo("42000");
        assertThat(f.getQty()).isEqualByComparingTo("0.1");
        assertThat(f.getFee()).isEqualByComparingTo("4.2");
        assertThat(f.getFeeCurrency()).isEqualTo("USDT");
        assertThat(f.getLiquidity()).isEqualTo("maker");
        assertThat(f.getExternalFillId()).isEqualTo("ext-1");
        assertThat(f.getFilledAt()).isEqualTo(t);
    }

    @Test
    void settersAllowMyBatisPopulation() {
        Fill f = new Fill();
        f.setId(99L);
        f.setOrderId(1L);
        f.setAccountId(2L);
        f.setSymbol("ETH/USDT");
        f.setSide(OrderSide.SELL);
        f.setPrice(new BigDecimal("3000"));
        f.setQty(new BigDecimal("1.0"));
        f.setFee(new BigDecimal("6"));
        f.setFeeCurrency("USDT");
        f.setLiquidity("taker");
        f.setExternalFillId("ext-2");
        f.setFilledAt(Instant.parse("2026-06-30T00:00:00Z"));
        assertThat(f.getId()).isEqualTo(99L);
        assertThat(f.getSymbol()).isEqualTo("ETH/USDT");
    }
}
