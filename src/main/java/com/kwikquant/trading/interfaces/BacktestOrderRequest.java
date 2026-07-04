package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.MarketSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "canonical symbol", example = "BTC/USDT", requiredMode = Schema.RequiredMode.REQUIRED)
                String symbol,
        @Schema(description = "方向（枚举: BUY | SELL）", example = "BUY", requiredMode = Schema.RequiredMode.REQUIRED)
                OrderSide side,
        @Schema(
                        description = "订单类型（枚举: LIMIT | MARKET | STOP | STOP_LIMIT）",
                        example = "LIMIT",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                OrderType orderType,
        @Schema(description = "下单数量（>0，精度 8 位）", example = "0.1", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal amount,
        @Schema(description = "限价（LIMIT 必填，MARKET 为 null，精度 8 位）", example = "42150.50") BigDecimal price,
        @Schema(description = "市场类型（枚举: SPOT | FUTURES）", example = "SPOT", requiredMode = Schema.RequiredMode.REQUIRED)
                MarketType marketType,
        @Schema(
                        description = "交易所（枚举: BINANCE | OKX | BYBIT | PAPER）",
                        example = "BINANCE",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Exchange exchange,
        @Schema(description = "当前 bar OHLC 快照，撮合输入", requiredMode = Schema.RequiredMode.REQUIRED)
                MarketSnapshot snapshot) {}
