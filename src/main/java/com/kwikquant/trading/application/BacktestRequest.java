package com.kwikquant.trading.application;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.MatchConfig;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Backtest 任务请求。本 Wave 简化：传入预编排 orderIntents（Wave 6 后由策略代码实时产出）。
 *
 * <p>{@code initialCapital} 是虚拟账本余额（quote 货币如 USDT），临时账本仅在回测内存中存在，跑完即丢。
 */
public record BacktestRequest(
        Long taskId,
        String strategyCode,
        com.kwikquant.shared.types.Exchange exchange,
        com.kwikquant.shared.types.MarketType marketType,
        String symbol,
        com.kwikquant.shared.types.Interval interval,
        Instant start,
        Instant end,
        BigDecimal initialCapital,
        MatchConfig matchConfig,
        java.util.List<OrderIntent> orderIntents) {

    /**
     * 单条订单意图。Backtest 在 bar 时间到达时激活。
     *
     * @param activateAt 订单激活时间（不早于该 bar 的 openTime）
     */
    public record OrderIntent(
            Instant activateAt,
            String clientOrderId,
            OrderSide side,
            OrderType orderType,
            BigDecimal amount,
            BigDecimal price,
            BigDecimal stopPrice,
            TimeInForce timeInForce,
            Instant expireAt) {

        public OrderSubmitCommand toCommand(long accountId, String symbol, com.kwikquant.shared.types.MarketType mt) {
            return new OrderSubmitCommand(
                    accountId,
                    symbol,
                    mt,
                    side,
                    orderType,
                    amount,
                    price,
                    stopPrice,
                    timeInForce,
                    expireAt,
                    clientOrderId);
        }
    }
}
