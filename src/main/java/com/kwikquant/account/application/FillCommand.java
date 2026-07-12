package com.kwikquant.account.application;

import com.kwikquant.shared.types.OrderSide;
import java.math.BigDecimal;

public record FillCommand(
        long accountId,
        boolean paperTrading,
        OrderSide side,
        String symbol,
        BigDecimal qty,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal frozenQuoteAmount) {}
