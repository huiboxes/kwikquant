package com.kwikquant.risk.domain;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import java.math.BigDecimal;

/**
 * Immutable request payload for a pre-trade risk check.
 *
 * <p>加 {@code marketType}/{@code leverage}/{@code availableMargin} 三字段,
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
 * @param dailyRealizedPnl  today's net realized PnL including signed fee costs (negative = loss); used by DAILY_LOSS_LIMIT
 * @param marketType        SPOT / PERP;PERP 才走 MAX_INITIAL_MARGIN
 * @param leverage          PERP 杠杆;SPOT null。initialMargin = notional / leverage
 * @param availableMargin   账户可用保证金(symbol quote 币种 free 余额,TradingService submit 调 risk 前查 balance 填);
 *                          SPOT/null 时 MAX_INITIAL_MARGIN 对 PERP 无法评 → 兜底默认 ratio(80%)
 * @param totalBalance      账户总权益(symbol quote 币种 total 余额,严格前瞻用);严格前瞻求和:
 *                          {@code used = totalBalance - availableMargin}(现有持仓占用,交易所真相),
 *                          校验 {@code used + initialMargin <= totalBalance × ratio}(下单后总占用 <= ratio)。
 *                          比"本单 initialMargin <= free × ratio"更严(used 大时拦住后者放过的单)。
 *                          SPOT/null → MAX_INITIAL_MARGIN fail-closed。
 * @param marginMode        PERP 保证金模式(ISOLATED/CROSS);SPOT null。MaxInitialMarginEvaluator CROSS 分流:
 *                          CROSS 时 used = crossAccountInitialMarginSum(现有仓 frozenAmount 之和),
 *                          ISOLATED/null 时 used = totalBalance - availableMargin(原逻辑)。
 * @param crossAccountInitialMarginSum CROSS 时 TradingService 查
 *                          PositionMapper.sumFrozenByAccountAndMarginMode 填(现有仓 frozenAmount 之和);
 *                          ISOLATED/SPOT null(risk 模块不能依赖 trading.infrastructure,数据由 TradingService 传入)。
 * @param reduceOnly        减仓/平仓意图(TradingService 派生:PERP 按 positionEffect/isReduceOnly;
 *                          SPOT 须非 flat 持仓且方向反向——保护性类型(STOP/TP/TRAILING)直接认定,
 *                          普通 LIMIT/MARKET 需 LONG 持仓 SELL 且 amount ≤ 持仓 qty;超卖与
 *                          SHORT 现货行(账本异常态)的 BUY 实质新增敞口,不认定)。
 *                          MAX_INITIAL_MARGIN、DAILY_LOSS_LIMIT 与 MAX_NOTIONAL 对 reduce-only 单
 *                          短路放行——"风控不拦退出通道":ISOLATED 锁定保证金使 used 反映存量占用后,
 *                          平仓单再按 notional/leverage 计一份 initialMargin 参与占用求和,占用 &gt;40%
 *                          的仓位会被自己的平仓单拒掉,唯一出路只剩强平(用户被推向最大损失出口);
 *                          SPOT 同理,日损/名义额触顶后拦住持仓内卖单 = 强迫持有继续放血。
 *                          ORDER_FREQUENCY 有意不消费(频率规则对退出单仍生效,防豁免通道被刷单滥用)。
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
        BigDecimal totalBalance,
        MarginMode marginMode,
        BigDecimal crossAccountInitialMarginSum,
        boolean reduceOnly,
        String requestId) {

    /** 兼容构造:不携带 reduce-only 意图的调用点(dry-run/存量测试)按 false 评估(原行为)。 */
    public RiskCheckRequest(
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
            BigDecimal totalBalance,
            MarginMode marginMode,
            BigDecimal crossAccountInitialMarginSum,
            String requestId) {
        this(
                orderId,
                accountId,
                userId,
                symbol,
                side,
                orderType,
                amount,
                price,
                notionalValue,
                recentOrderCount,
                dailyRealizedPnl,
                marketType,
                leverage,
                availableMargin,
                totalBalance,
                marginMode,
                crossAccountInitialMarginSum,
                false,
                requestId);
    }
}
