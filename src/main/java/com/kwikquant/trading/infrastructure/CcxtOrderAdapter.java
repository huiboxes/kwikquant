package com.kwikquant.trading.infrastructure;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.trading.domain.Order;
import java.util.List;
import java.util.function.Consumer;

/**
 * CCXT 真撮合的 adapter 接口。隔离 LiveExecutor 与 CCXT API 细节，便于 mock 测试 + 应对 spike S1（sandbox 切换）/ S2
 * （私有 WS）未验证场景。
 *
 * <p>注意：实现负责管理 per-account CCXT 实例 + API key 解密（仅进程内存）+ sandbox 配置切换。
 */
public interface CcxtOrderAdapter {

    /** 在交易所创建订单。返回 exchange_order_id。 */
    String createOrder(ExchangeAccount account, Order order);

    /** 撤销订单。 */
    void cancelOrder(ExchangeAccount account, Order order);

    /** 启动快照：fetchOpenOrders + fetchPositions 对账。 */
    AccountSnapshot fetchSnapshot(ExchangeAccount account);

    /** 注册 fill push 监听器（WS）。返回 unsubscribe Runnable。 */
    Runnable subscribeFills(ExchangeAccount account, Consumer<FillEvent> consumer);

    /** Live 模式的 Snapshot 数据，用于 startup 对账。 */
    record AccountSnapshot(List<OrderSnapshot> openOrders, List<PositionSnapshot> positions) {}

    record OrderSnapshot(
            String exchangeOrderId,
            String clientOrderId,
            String symbol,
            String side,
            java.math.BigDecimal amount,
            java.math.BigDecimal filledQty,
            String status) {}

    record PositionSnapshot(
            String symbol, String side, java.math.BigDecimal qty, java.math.BigDecimal entryPrice) {}

    /** Fill push 事件。 */
    record FillEvent(
            long orderId,
            String exchangeOrderId,
            String externalFillId,
            java.math.BigDecimal price,
            java.math.BigDecimal qty,
            java.math.BigDecimal fee,
            String feeCurrency,
            String liquidity,
            java.time.Instant filledAt) {}
}
