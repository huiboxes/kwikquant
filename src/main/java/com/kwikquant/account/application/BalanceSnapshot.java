package com.kwikquant.account.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;

public record BalanceSnapshot(
        @Schema(description = "余额快照，key=币种代码，value=该币种余额明细", example = "{\"USDT\":{\"free\":\"100000\",\"used\":\"0\",\"total\":\"100000\"}}")
                Map<String, CurrencyBalance> currencies) {

    public record CurrencyBalance(
            @Schema(description = "可用余额（精度 8 位）", example = "100000") BigDecimal free,
            @Schema(description = "冻结余额（精度 8 位）", example = "0") BigDecimal used,
            @Schema(description = "总余额（精度 8 位）", example = "100000") BigDecimal total) {}

    static BalanceSnapshot paper() {
        return new BalanceSnapshot(Map.of(
                "USDT", new CurrencyBalance(new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"))));
    }
}
