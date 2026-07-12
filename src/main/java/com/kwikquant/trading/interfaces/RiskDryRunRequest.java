package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 风控预检请求体。字段与真实下单（{@code OrderSubmitRequest}）的对应项同源，
 * 让 dry-run 能精确模拟一次 submit 的风控输入。
 *
 * @param accountId  交易所账户 ID
 * @param symbol     交易对，CCXT 规范，如 {@code BTC/USDT}
 * @param side       买卖方向
 * @param orderType  订单类型（MARKET / LIMIT / ...）
 * @param amount     数量
 * @param price      限价；MARKET 单传 null
 * @param marketType 市场类型 SPOT / PERP
 */
public record RiskDryRunRequest(
        @Schema(description = "账户 ID", example = "7") long accountId,
        @NotBlank @Schema(description = "交易对，CCXT 规范", example = "BTC/USDT") String symbol,
        @NotNull @Schema(description = "买卖方向 BUY | SELL") OrderSide side,
        @NotNull @Schema(description = "订单类型 MARKET | LIMIT | ...") OrderType orderType,
        @NotNull @Positive @Schema(description = "数量", example = "0.1") BigDecimal amount,
        @Schema(description = "限价；MARKET 单传 null", example = "42000") BigDecimal price,
        @NotNull @Schema(description = "市场类型 SPOT | PERP") MarketType marketType) {}
