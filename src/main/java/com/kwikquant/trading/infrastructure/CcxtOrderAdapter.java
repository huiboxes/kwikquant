package com.kwikquant.trading.infrastructure;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
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

    /**
     * 设置杠杆(实盘 PERP)。{@code posSide} 用于 OKX 双向持仓模式(§10 M18)。
     *
     * <p>Live 模式 per (account, symbol, marginMode) 缓存,变更才调,再 createOrder(§4.1)。
     * 4a.1 契约占位,真实实现在 4a.3。
     *
     * @param account  交易所账号
     * @param symbol   CCXT 规范符号(如 BTC/USDT:USDT)
     * @param leverage 杠杆倍数
     * @param mode     保证金模式(ISOLATED/CROSS)
     * @param posSide  持仓方向(LONG/SHORT);单向持仓模式可传 null
     */
    void setLeverage(ExchangeAccount account, String symbol, int leverage, MarginMode mode, PositionSide posSide);

    /**
     * 设置保证金模式(ISOLATED/CROSS)。实盘 PERP(§4.1)。
     *
     * <p>4a.3 真实实现:spike 验证 OKX {@code setMarginMode} API 强制要求 {@code lever} 参数,否则
     * BadRequest "lever should be 1-125"(即使 setLeverage 已调用,该 param 仍必填)。故契约扩
     * {@code leverage} 形参,由 LiveExecutor(4a.5) per (account,symbol,marginMode) 缓存注入。
     *
     * @param account   交易所账号
     * @param symbol    CCXT 规范符号
     * @param mode      保证金模式
     * @param leverage  当前杠杆倍数(1-125,OKX setMarginMode API 必填)
     */
    void setMarginMode(ExchangeAccount account, String symbol, MarginMode mode, int leverage);

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

    /**
     * 交易所持仓快照(实盘 fetchPositions 回填)。
     *
     * <p>字段语义参考 CCXT types.Position(§4.1):
     * <ul>
     *   <li>SPOT 持仓: {@code marketType=SPOT},{@code positionSide/leverage/marginMode/liquidationPrice/
     *       markPrice/maintMargin/unrealizedPnl} 均 null。</li>
     *   <li>PERP 持仓: 上述字段按交易所返回填;{@code unrealizedPnl} 从交易所拉,不本地派生。</li>
     * </ul>
     *
     * <p><strong>不加 marginBalance 字段</strong>(§12 B1-s 推翻 M12):逐仓强平判 position.frozenAmount +
     * 派生 unrealizedPnl,不冗余存;CCXT types.Position 也只有 maintenanceMargin/marginRatio,无 marginBalance。
     */
    record PositionSnapshot(
            String symbol,
            String side,
            java.math.BigDecimal qty,
            java.math.BigDecimal entryPrice,
            MarketType marketType,
            PositionSide positionSide,
            Integer leverage,
            MarginMode marginMode,
            java.math.BigDecimal liquidationPrice,
            java.math.BigDecimal markPrice,
            java.math.BigDecimal maintMargin,
            java.math.BigDecimal unrealizedPnl) {}

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
