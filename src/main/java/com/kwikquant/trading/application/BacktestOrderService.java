package com.kwikquant.trading.application;

import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.infra.BacktestLedgerLifecycle;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.BacktestOrderRejectedException;
import com.kwikquant.trading.domain.BacktestTaskNotRunningException;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.MarketSnapshot;
import com.kwikquant.trading.domain.MatchConfig;
import com.kwikquant.trading.domain.MatchingKernel;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.TimeInForce;
import com.kwikquant.trading.interfaces.BacktestOrderRequest;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 回测 single-order executor(§3.1)。接收 Worker 逐 bar 推来的 order+snapshot,调 {@link MatchingKernel} 撮合,
 * 维护 per-taskId 内存虚拟账本,返回 Fill。**不经 OrderRouter**(回测无真实 ExchangeAccount)。
 *
 * <p>plan-外决策(避 trading→strategy 模块违规):
 * <ul>
 *   <li>7303 "task not RUNNING" 用 ledger 存在性判定(initLedger/cleanupLedger 生命周期 = task RUNNING 生命周期),
 *   不反查 strategy 的 BacktestTaskMapper</li>
 *   <li>不推 per-bar on_fill WS(Worker 从 HTTP response 取 Fill;Dashboard 看最终结果 via §3.6 /topic/backtests/{userId})</li>
 * </ul>
 */
@Service
public class BacktestOrderService implements BacktestLedgerLifecycle {

    private static final long PSEUDO_ACCOUNT_ID = 0L;

    private final ConcurrentHashMap<Long, BacktestLedger> ledgers = new ConcurrentHashMap<>();

    /** 回测任务启动时初始化虚拟账本(BacktestExecutionGateway 调,§3.6,SPI)。 */
    @Override
    public void initLedger(long taskId, BigDecimal initialCapital) {
        ledgers.put(taskId, new BacktestLedger(initialCapital));
    }

    /** 回测任务结束清理账本(BacktestExecutionGateway finally 调,§3.6,SPI,幂等)。 */
    @Override
    public void cleanupLedger(long taskId) {
        ledgers.remove(taskId);
    }

    /**
     * 处理 Worker 推来的回测下单。返回 Fill;未撮合(LIMIT 未穿越)返回 null;账本不足抛
     * {@link BacktestOrderRejectedException}(7302);task 不在 RUNNING 抛 {@link BacktestTaskNotRunningException}(7303)。
     */
    public Fill submit(long taskId, BacktestOrderRequest request) {
        BacktestLedger ledger = ledgers.get(taskId);
        if (ledger == null) {
            throw new BacktestTaskNotRunningException("backtest task " + taskId + " not running (no ledger)");
        }
        Order order = buildOrder(ledger, request);
        MarketSnapshot snapshot = request.snapshot();
        Optional<Fill> matched = MatchingKernel.match(order, snapshot, MatchConfig.defaults());
        if (matched.isEmpty()) {
            return null;
        }
        Fill fill = matched.get();
        if (!ledger.canApply(fill)) {
            throw new BacktestOrderRejectedException("insufficient balance/inventory for task " + taskId);
        }
        ledger.apply(fill);
        return fill;
    }

    private Order buildOrder(BacktestLedger ledger, BacktestOrderRequest req) {
        TradingPairInfo pair = pseudoPair(req.exchange(), req.marketType(), req.symbol());
        OrderSubmitCommand cmd = new OrderSubmitCommand(
                PSEUDO_ACCOUNT_ID,
                req.symbol(),
                req.marketType(),
                req.side(),
                req.orderType(),
                req.amount(),
                req.price(),
                null,
                TimeInForce.GTC,
                null,
                null);
        Order order = Order.create(cmd, pair);
        order.setExchange(pair.exchange());
        order.setId(ledger.nextOrderId());
        order.setStatus(OrderStatus.SUBMITTED);
        return order;
    }

    private static TradingPairInfo pseudoPair(Exchange exchange, MarketType marketType, String symbol) {
        String base = symbol.contains("/") ? symbol.substring(0, symbol.indexOf('/')) : symbol;
        String quote = symbol.contains("/") ? symbol.substring(symbol.indexOf('/') + 1) : "USDT";
        return new TradingPairInfo(
                exchange,
                marketType,
                symbol,
                base,
                quote,
                new BigDecimal("0.0000001"),
                new BigDecimal("1000000"),
                null,
                null,
                true);
    }
}
