package com.kwikquant.risk.domain;

import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;

/**
 * Immutable request payload for a pre-trade risk check.
 *
 * <p>阶段2h(§10 M7/§11 M11-impl)加 {@code marketType}/{@code leverage}/{@code availableMargin} 三字段,
 * 供 {@link com.kwikquant.risk.domain.evaluators.MaxInitialMarginEvaluator} 评 PERP 初始保证金占用
 * (initialMargin = notional / leverage &lt;= availableMargin × ratio)。SPOT 调用点 marketType=SPOT/leverage=null/
 * availableMargin=null(三 null,MaxInitialMarginEvaluator 对 SPOT 不评;MaxNotionalEvaluator SPOT 走原逻辑)。
 *
 * @param orderId           internal order id
 * @param accountId         exchange account id
 * @param userId            owning user id
 * @param symbol            trading pair symbol, e.g. "BTC/USDT"
 * @param side              order side (BUY / SELL)
 * @param orderType         order type (MARKET / LIMIT / ...)
 * @param amount            order quantity
 * @param price             limit price (may be null for market orders)
 * @param notionalValue     estimated notional value in USDT 估值口径 (symbol quote 币种数值,USDT-only 配置下即 USDT;may be null if unavailable)
 * @param recentOrderCount  number of orders submitted by this account in the last 60s
 *                          (for ORDER_FREQUENCY; computed by TradingService so risk stays
 *                          free of trading-module dependencies)
 * @param dailyRealizedPnl  net cashflow from today's fills (negative = loss); used by DAILY_LOSS_LIMIT
 * @param marketType        SPOT / PERP;PERP 才走 MAX_INITIAL_MARGIN(§10 M7)
 * @param leverage          PERP 杠杆;SPOT null。initialMargin = notional / leverage
 * @param availableMargin   账户可用保证金(symbol quote 币种 free 余额,TradingService submit 调 risk 前查 balance 填);
 *                          SPOT/null 时 MAX_INITIAL_MARGIN 对 PERP 无法评 → 兜底默认 ratio(§12 m1-s 80%)
 * @param requestId         idempotency key for the risk check
 */
public record RiskCheckRequest(
        long orderId,
        long accountId,
        long userId,
        String symbol,
        OrderSide side,
        OrderType orderType,
        BigDecimal amount,
        BigDecimal price,
        BigDecimal notionalValue,
        int recentOrderCount,
        BigDecimal dailyRealizedPnl,
        MarketType marketType,
        Integer leverage,
        BigDecimal availableMargin,
        String requestId) {}
