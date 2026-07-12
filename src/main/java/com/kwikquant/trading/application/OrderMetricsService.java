package com.kwikquant.trading.application;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * 下单前的可量化指标计算：名义额、近 60s 下单数、当日已实现盈亏、MARKET BUY 取价。
 *
 * <p>从 {@link TradingService#submit} 抽出，让风控预检端点 {@code POST /api/v1/risk/dry-run}
 * 复用与真实下单完全相同的计算路径，保证 dry-run verdict 与真实 submit 会得到的 verdict 一致
 * （faithful preview，无漂移）——这是预检端点可信度的核心保证。
 *
 * <p>无副作用：只读 orderMapper / fillMapper / marketDataService，不改任何状态、不落库。
 */
@Service
public class OrderMetricsService {

    private final OrderMapper orderMapper;
    private final FillMapper fillMapper;
    private final MarketDataService marketDataService;

    public OrderMetricsService(OrderMapper orderMapper, FillMapper fillMapper, MarketDataService marketDataService) {
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.marketDataService = marketDataService;
    }

    /**
     * MARKET BUY 需用最新成交价估算名义额（submit 与 dry-run 共用同一取价逻辑）。
     * 仅当无限价（{@code limitPrice == null}，即 MARKET）且 BUY 时取 {@code ticker.last()}；
     * 其余返回 null。SELL MARKET 不预冻结估算，与 submit 原行为保持一致。
     */
    public BigDecimal resolveMarketPrice(
            ExchangeAccount account, OrderSide side, String symbol, MarketType marketType, BigDecimal limitPrice) {
        if (limitPrice != null || side != OrderSide.BUY) {
            return null;
        }
        Ticker ticker = marketDataService.getLatestTicker(account.getExchange(), marketType, symbol);
        return (ticker != null) ? ticker.last() : null;
    }

    /**
     * 名义额 = {@code amount × (price ?? marketPrice)}；价格不可得时返回 null（与原
     * {@code TradingService.computeNotional} 行为一致）。
     */
    public BigDecimal notional(BigDecimal amount, BigDecimal price, BigDecimal marketPrice) {
        BigDecimal effective = (price != null) ? price : marketPrice;
        return (effective != null) ? amount.multiply(effective) : null;
    }

    /**
     * 近 60s 该账户提交的订单数（含当前单：maxPerMinute=N 允许每分钟 N 单）。
     */
    public int countRecentOrders(long accountId) {
        return (int) orderMapper.countByAccountSince(accountId, Instant.now().minusSeconds(60));
    }

    /**
     * 当日（UTC 日）已实现盈亏（净现金流，负=亏损），供 {@code DAILY_LOSS_LIMIT} 规则用。
     */
    public BigDecimal dailyRealizedPnl(long accountId) {
        return fillMapper.sumNetCashflow(accountId, Instant.now().truncatedTo(ChronoUnit.DAYS));
    }

    /**
     * MARKET BUY 且无法解析市价 → true。submit 与 dry-run 共用此判定，避免 null notional 绕过
     * MAX_NOTIONAL 或 dry-run 返 false-APPROVE（faithfulness 漂移）。纯判定，无副作用。
     */
    public boolean marketBuyLacksPrice(OrderType orderType, OrderSide side, BigDecimal resolvedMarketPrice) {
        return orderType == OrderType.MARKET && side == OrderSide.BUY && resolvedMarketPrice == null;
    }

    /**
     * dry-run 预演用：模拟"提交此单后"的近 60s 计数 = {@code countRecentOrders + 1}。
     *
     * <p>submit 在 {@code insertOrder}（{@code REQUIRES_NEW} 独立事务）<b>之后</b>调
     * {@link #countRecentOrders}，因独立事务已 commit，计数必然含当前单（N+1）。dry-run 不 insert，
     * 故 {@code +1} 精确还原 submit 的 N+1（非近似）。这消除 ORDER_FREQUENCY 临界点的
     * off-by-one 漂移（dry-run N 放行 / submit N+1 拒绝的 false-APPROVE）。
     */
    public int previewRecentOrderCount(long accountId) {
        return countRecentOrders(accountId) + 1;
    }
}
