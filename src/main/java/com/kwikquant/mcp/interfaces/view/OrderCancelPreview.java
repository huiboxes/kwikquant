package com.kwikquant.mcp.interfaces.view;

/** cancel_order 两阶段确认预览:待撤订单要素回显。 */
public record OrderCancelPreview(
        Long orderId, Long accountId, String symbol, String side, String orderType, String amount, String status) {}
