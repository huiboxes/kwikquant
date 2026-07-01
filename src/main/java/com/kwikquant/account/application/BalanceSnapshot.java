package com.kwikquant.account.application;

import java.math.BigDecimal;
import java.util.Map;

public record BalanceSnapshot(Map<String, CurrencyBalance> currencies) {

    public record CurrencyBalance(BigDecimal free, BigDecimal used, BigDecimal total) {}

    static BalanceSnapshot paper() {
        return new BalanceSnapshot(Map.of(
                "USDT", new CurrencyBalance(new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"))));
    }
}
