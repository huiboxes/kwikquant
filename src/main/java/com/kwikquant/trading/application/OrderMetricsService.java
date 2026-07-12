package com.kwikquant.trading.application;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
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
}
