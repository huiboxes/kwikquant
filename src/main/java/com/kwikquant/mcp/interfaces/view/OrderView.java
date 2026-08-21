package com.kwikquant.mcp.interfaces.view;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.application.OrderCancelResult;
import com.kwikquant.trading.application.OrderSubmitResult;
import com.kwikquant.trading.domain.Order;
import java.time.Instant;

/**
 * MCP 订单工具返回投影。{@code status} 用 String 而非 {@link OrderStatus} 枚举,以便携带
 * {@code RISK_REJECTED}(风控拒绝是业务结果非订单状态机状态,OrderStatus 枚举无此值;trading 模块
 * 风控拒绝时 Order.status=CANCELLED/REJECTED + 抛 RiskRejectedException,MCP 工具层 catch 后用
 * RISK_REJECTED 显式标识给 Agent)。
 *
 * <p>{@code reason} 仅风控拒绝时填充,其余场景 null。
 *
 * <p>合约字段(marketType/side/orderType/leverage/marginMode/positionEffect)仅 {@link #from(Order)}
 * 填充(get_open_orders / 撤单后回查路径);{@link #from(OrderSubmitResult)} /
 * {@link #from(OrderCancelResult)} 返 null(submit/cancel 结果只含 orderId/status/version,
 * Agent 需合约详情可调 get_open_orders)。marginMode/positionEffect SPOT 订单为 null。
 */
public record OrderView(
        long orderId,
        String status,
        String marketType,
        String side,
        String orderType,
        String amount,
        String price,
        String filledQty,
        String filledAvgPrice,
        Integer leverage,
        String marginMode,
        String positionEffect,
        long version,
        Instant createdAt,
        String reason) {

    public static OrderView from(OrderSubmitResult r) {
        return new OrderView(
                r.orderId(),
                r.status().name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                r.version(),
                r.createdAt(),
                null);
    }

    public static OrderView from(OrderCancelResult r) {
        return new OrderView(
                r.orderId(),
                r.status().name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                r.version(),
                null,
                null);
    }

    public static OrderView from(Order o) {
        return new OrderView(
                o.getId(),
                o.getStatus().name(),
                o.getMarketType() != null ? o.getMarketType().name() : null,
                o.getSide() != null ? o.getSide().name() : null,
                o.getOrderType() != null ? o.getOrderType().name() : null,
                str(o.getAmount()),
                str(o.getPrice()),
                str(o.getFilledQty()),
                str(o.getFilledAvgPrice()),
                o.getLeverage(),
                o.getMarginMode() != null ? o.getMarginMode().name() : null,
                o.getPositionEffect() != null ? o.getPositionEffect().name() : null,
                o.getVersion(),
                o.getCreatedAt(),
                null);
    }

    /** 风控拒绝:TradingService.submit 抛 RiskRejectedException 时,工具层 catch 后构造此视图返 200。 */
    public static OrderView riskRejected(long orderId, String reason) {
        return new OrderView(
                orderId, "RISK_REJECTED", null, null, null, null, null, null, null, null, null, null, 0, null, reason);
    }

    /** 金额红线:MCP 通道金额一律字符串输出(toPlainString 保精度),null 透传 null。 */
    private static String str(java.math.BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
