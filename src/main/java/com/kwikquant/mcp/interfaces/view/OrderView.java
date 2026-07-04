package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.application.OrderCancelResult;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP 订单工具返回投影。{@code status} 用 String 而非 {@link OrderStatus} 枚举，以便携带
 * {@code RISK_REJECTED}（风控拒绝是业务结果非订单状态机状态，OrderStatus 枚举无此值；trading 模块
 * 风控拒绝时 Order.status=CANCELLED/REJECTED + 抛 RiskRejectedException，MCP 工具层 catch 后用
 * RISK_REJECTED 显式标识给 Agent）。
 *
 * <p>{@code reason} 仅风控拒绝时填充，其余场景 null。
 */
public record OrderView(
        long orderId,
        String status,
        BigDecimal amount,
        BigDecimal price,
        BigDecimal filledQty,
        BigDecimal filledAvgPrice,
        long version,
        Instant createdAt,
        String reason) {

    public static OrderView from(OrderSubmitResult r) {
        return new OrderView(r.orderId(), r.status().name(), null, null, null, null, r.version(), r.createdAt(), null);
    }

    public static OrderView from(OrderCancelResult r) {
        return new OrderView(r.orderId(), r.status().name(), null, null, null, null, r.version(), null, null);
    }

    public static OrderView from(Order o) {
        return new OrderView(
                o.getId(),
                o.getStatus().name(),
                o.getAmount(),
                o.getPrice(),
                o.getFilledQty(),
                o.getFilledAvgPrice(),
                o.getVersion(),
                o.getCreatedAt(),
                null);
    }

    /** 风控拒绝：TradingService.submit 抛 RiskRejectedException 时，工具层 catch 后构造此视图返 200。 */
    public static OrderView riskRejected(long orderId, String reason) {
        return new OrderView(orderId, "RISK_REJECTED", null, null, null, null, 0, null, reason);
    }
}
