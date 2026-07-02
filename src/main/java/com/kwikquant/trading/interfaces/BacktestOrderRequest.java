package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.MarketSnapshot;
import java.math.BigDecimal;

/**
 * 回测下单请求(Worker→Java POST /api/v1/backtests/{taskId}/orders body,§3.1/§4.2)。
 *
 * <p>plan-外决策:含 {@code marketType}+{@code exchange}(Worker 从 task config 发,避免 trading 反查 strategy 的
 * BacktestTaskMapper,避 trading→strategy 模块违规)。{@code snapshot} 是 Worker 当前 bar OHLC,Java 直接用作 MatchingKernel 输入。
 *
 * @param symbol 交易对(BTC/USDT)
 * @param side BUY/SELL
 * @param orderType MARKET/LIMIT
 * @param amount 下单数量
 * @param price 限价(LIMIT 必填,MARKET null)
 * @param marketType SPOT/FUTURES 等
 * @param exchange 交易所
 * @param snapshot 当前 bar OHLC(撮合输入)
 */
public record BacktestOrderRequest(
        String symbol,
        OrderSide side,
        OrderType orderType,
        BigDecimal amount,
        BigDecimal price,
        MarketType marketType,
        Exchange exchange,
        MarketSnapshot snapshot) {}
