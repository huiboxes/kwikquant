package com.kwikquant.trading.application;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.MarketSnapshot;
import com.kwikquant.trading.domain.MatchConfig;
import com.kwikquant.trading.domain.MatchingKernel;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Backtest 模式 Executor。<strong>不实现 {@link Executor} 接口</strong>（无 ExchangeAccount 概念，由
 * BacktestTaskService Wave 6 驱动）。
 *
 * <p>核心流程：直连 Postgres 批量拉历史 K 线 → 内存临时账本 → 逐 bar 处理活跃订单 → MatchingKernel.match → 累积 fills + 更新虚拟持仓
 * → 返回 BacktestResult。
 *
 * <p>本 Wave 简化：(1) 只支持单 symbol 单交易对；(2) MatchingFidelity 固定 FAST（kline 数据源）；(3) 不写
 * orders/fills/positions 表；(4) PnL 简化为 quote 货币的余额变化。
 */
@Service
public class BacktestExecutor {

    private static final Logger log = LoggerFactory.getLogger(BacktestExecutor.class);

    private static final long PSEUDO_ACCOUNT_ID = 0L;

    private final MarketDataService marketDataService;

    @Autowired
    public BacktestExecutor(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public BacktestResult run(BacktestRequest req) {
        try {
            return runInternal(req);
        } catch (RuntimeException e) {
            log.error("[backtest] task {} failed: {}", req.taskId(), e.getMessage(), e);
            return new BacktestResult(req.taskId(), BacktestResult.Status.FAILED, List.of(), List.of(), e.getMessage());
        }
    }

    private BacktestResult runInternal(BacktestRequest req) {
        List<Kline> klines = marketDataService.getKlineRange(
                req.exchange(),
                req.marketType(),
                req.symbol(),
                req.interval(),
                req.start(),
                req.end());
        if (klines.isEmpty()) {
            return new BacktestResult(
                    req.taskId(),
                    BacktestResult.Status.FAILED,
                    List.of(),
                    List.of(),
                    "no klines in range");
        }

        BigDecimal cashBalance = req.initialCapital();
        BigDecimal baseInventory = BigDecimal.ZERO;
        BigDecimal avgEntryPrice = BigDecimal.ZERO;

        TradingPairInfo pair = pseudoPair(req.exchange(), req.marketType(), req.symbol());
        MatchConfig cfg = req.matchConfig() != null ? req.matchConfig() : MatchConfig.defaults();

        // 按 activateAt 时间顺序排好的活跃订单池
        Map<Long, Order> activeOrders = new HashMap<>();
        long nextOrderId = 1L;
        int intentIdx = 0;
        var sortedIntents = new ArrayList<>(req.orderIntents());
        sortedIntents.sort((a, b) -> a.activateAt().compareTo(b.activateAt()));

        List<Fill> allFills = new ArrayList<>();
        List<BacktestResult.EquityPoint> equityCurve = new ArrayList<>();

        for (Kline bar : klines) {
            Instant barTime = bar.openTime();
            // 激活到期的 orderIntents
            while (intentIdx < sortedIntents.size()
                    && !sortedIntents.get(intentIdx).activateAt().isAfter(barTime)) {
                var intent = sortedIntents.get(intentIdx++);
                OrderSubmitCommand cmd = intent.toCommand(PSEUDO_ACCOUNT_ID, req.symbol(), req.marketType());
                try {
                    Order o = Order.create(cmd, pair);
                    o.setId(nextOrderId++);
                    o.setStatus(OrderStatus.SUBMITTED); // backtest 简化：直接 SUBMITTED
                    activeOrders.put(o.getId(), o);
                } catch (RuntimeException e) {
                    log.warn("[backtest] order intent rejected: {}", e.getMessage());
                }
            }

            MarketSnapshot snap = MarketSnapshot.fromKline(bar);
            // 处理活跃订单：撮合 + GTD 检查 + IOC/FOK 检查
            var toRemove = new ArrayList<Long>();
            for (Order order : activeOrders.values()) {
                if (order.getStatus().isTerminal()) {
                    toRemove.add(order.getId());
                    continue;
                }
                // GTD 检查
                if (order.getTimeInForce() == TimeInForce.GTD
                        && order.getExpireAt() != null
                        && bar.openTime().isAfter(order.getExpireAt())) {
                    order.transitionTo(OrderStatus.EXPIRED);
                    toRemove.add(order.getId());
                    continue;
                }
                Optional<Fill> matched = MatchingKernel.match(order, snap, cfg);
                if (matched.isPresent()) {
                    Fill f = matched.get();
                    BigDecimal notional = f.getPrice().multiply(f.getQty());
                    if (order.getSide() == OrderSide.BUY) {
                        BigDecimal cost = notional.add(f.getFee());
                        if (cashBalance.compareTo(cost) < 0) {
                            order.transitionTo(OrderStatus.REJECTED);
                            toRemove.add(order.getId());
                            continue;
                        }
                        // 加仓加权均价
                        BigDecimal newInv = baseInventory.add(f.getQty());
                        if (baseInventory.signum() == 0) {
                            avgEntryPrice = f.getPrice();
                        } else {
                            avgEntryPrice = avgEntryPrice
                                    .multiply(baseInventory)
                                    .add(f.getPrice().multiply(f.getQty()))
                                    .divide(newInv, 8, java.math.RoundingMode.HALF_UP);
                        }
                        baseInventory = newInv;
                        cashBalance = cashBalance.subtract(cost);
                    } else {
                        // SELL: 检查 inventory 是否充足
                        if (baseInventory.compareTo(f.getQty()) < 0) {
                            order.transitionTo(OrderStatus.REJECTED);
                            toRemove.add(order.getId());
                            continue;
                        }
                        // 平仓部分 / 全部
                        BigDecimal proceeds = notional.subtract(f.getFee());
                        baseInventory = baseInventory.subtract(f.getQty());
                        cashBalance = cashBalance.add(proceeds);
                    }
                    order.accumulateFill(f.getQty(), f.getPrice());
                    OrderStatus next = order.remainingQty().signum() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
                    try {
                        order.transitionTo(next);
                    } catch (RuntimeException ignored) {
                        // PARTIALLY_FILLED → PARTIALLY_FILLED 是非法转换，但本 Wave 简化（v1 不部分成交）
                    }
                    allFills.add(f);
                    if (next == OrderStatus.FILLED) toRemove.add(order.getId());
                } else if (order.getTimeInForce() == TimeInForce.IOC || order.getTimeInForce() == TimeInForce.FOK) {
                    // 本 bar 未成 → IOC/FOK 立即撤
                    order.transitionTo(OrderStatus.CANCELLED);
                    toRemove.add(order.getId());
                }
            }
            toRemove.forEach(activeOrders::remove);

            // 记录 equity（cash + inventory * close）
            BigDecimal equity = cashBalance.add(baseInventory.multiply(bar.close()));
            equityCurve.add(new BacktestResult.EquityPoint(barTime, equity));
        }

        log.info(
                "[backtest] task={} bars={} trades={} final_equity={}",
                req.taskId(),
                klines.size(),
                allFills.size(),
                equityCurve.isEmpty() ? null : equityCurve.get(equityCurve.size() - 1).equity());

        // 抑制 unused 警告
        if (avgEntryPrice == null) avgEntryPrice = BigDecimal.ZERO;

        return new BacktestResult(req.taskId(), BacktestResult.Status.COMPLETED, allFills, equityCurve, null);
    }

    /** 临时 TradingPairInfo，用于 Order.create() 校验。回测无 TradingPairService 依赖，简化构造。 */
    private static TradingPairInfo pseudoPair(Exchange exchange, MarketType marketType, String symbol) {
        return new TradingPairInfo(
                exchange,
                marketType,
                symbol,
                symbol.contains("/") ? symbol.substring(0, symbol.indexOf('/')) : symbol,
                symbol.contains("/") ? symbol.substring(symbol.indexOf('/') + 1) : "USDT",
                new BigDecimal("0.0000001"),
                new BigDecimal("1000000"),
                null,
                null,
                true);
    }
}
