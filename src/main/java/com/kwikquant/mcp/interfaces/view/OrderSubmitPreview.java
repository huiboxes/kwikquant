package com.kwikquant.mcp.interfaces.view;

import java.math.BigDecimal;

/** submit_order 两阶段确认预览:订单要素回显(实盘账户下单前人类核对用)。 */
public record OrderSubmitPreview(
        Long accountId,
        String accountName,
        String marketType,
        String symbol,
        String side,
        String orderType,
        BigDecimal amount,
        BigDecimal price,
        Integer leverage,
        String marginMode,
        String positionEffect,
        String clientOrderId) {}
