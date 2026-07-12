package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.IllegalOrderStateTransitionException;
import com.kwikquant.trading.domain.MarketSnapshot;
import com.kwikquant.trading.domain.MatchConfig;
import com.kwikquant.trading.domain.MatchingKernel;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.OrderEvent;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Paper trading 模式 Executor。
 *
 * <p>实现：(1) submit 时把 Order 加入内存活跃订单池 + 推进 PENDING_NEW → SUBMITTED；(2) 监听 MarketDataService ticker
 * 推送 → 对该 symbol 的所有活跃订单调 MatchingKernel.match → ExecutionService.processExecutionReport；(3) cancel
 * 直接转 CANCELLED。
 *
 * <p>本 Wave 使用 SPREAD fidelity（last/bid/ask）；DEPTH (orderbook) 需要 Wave 3 反向补 CcxtOrderbookWorker，留待后续。
 *
 * <p>活跃订单池在内存，启动时通过 {@link #bootstrapActivePaperOrders} 从 DB 加载。
 */
@Component
@Primary
public class PaperExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(PaperExecutor.class);

    private final MarketDataService marketDataService;
    private final OrderMapper orderMapper;
    private final ExecutionService executionService;
    private final OrderWebSocketBroadcaster wsBroadcaster;
    private final ExchangeAccountService accountService;
    private final MatchConfig matchConfig = MatchConfig.spread();

    /** key = orderId, value = Order. 内存活跃订单池。 */
    private final ConcurrentMap<Long, Order> activeOrders = new ConcurrentHashMap<>();

    @Autowired
    public PaperExecutor(
            MarketDataService marketDataService,
            OrderMapper orderMapper,
            ExecutionService executionService,
            OrderWebSocketBroadcaster wsBroadcaster,
            ExchangeAccountService accountService) {
        this.marketDataService = marketDataService;
        this.orderMapper = orderMapper;
        this.executionService = executionService;
        this.wsBroadcaster = wsBroadcaster;
        this.accountService = accountService;
    }

    @PostConstruct
    void registerListener() {
        marketDataService.addTickerListener(this::onTicker);
    }

    @Override
    public void submit(Order order) {
        // 推进 NEW → PENDING_NEW → SUBMITTED（Paper 即时受理）
        try {
            order.transitionTo(OrderStatus.PENDING_NEW);
            int affected = orderMapper.casUpdate(order);
            if (affected != 1) {
                log.warn("[paper] submit CAS to PENDING_NEW failed: orderId={}", order.getId());
                return;
            }
            order.setVersion(order.getVersion() + 1);
            order.transitionTo(OrderStatus.SUBMITTED);
            affected = orderMapper.casUpdate(order);
            if (affected != 1) {
                // CAS 冲突 → 重读后尝试再次推进（防御性恢复，避免卡在 PENDING_NEW）
                log.warn("[paper] submit CAS to SUBMITTED failed, re-reading: orderId={}", order.getId());
                Order reloaded = orderMapper.findById(order.getId());
                if (reloaded != null && reloaded.getStatus() == OrderStatus.PENDING_NEW) {
                    reloaded.transitionTo(OrderStatus.SUBMITTED);
                    affected = orderMapper.casUpdate(reloaded);
                    if (affected == 1) {
                        reloaded.setVersion(reloaded.getVersion() + 1);
                        activeOrders.put(reloaded.getId(), reloaded);
                        log.info("[paper] order submitted after retry: orderId={}", reloaded.getId());
                        return;
                    }
                }
                log.error(
                        "[paper] submit recovery failed: orderId={} status={}",
                        order.getId(),
                        reloaded != null ? reloaded.getStatus() : "null");
                return;
            }
            order.setVersion(order.getVersion() + 1);
        } catch (IllegalOrderStateTransitionException e) {
            log.warn("[paper] submit transition rejected: orderId={} from={} to={}", order.getId(), e.from(), e.to());
            return;
        }

        activeOrders.put(order.getId(), order);
        log.info("[paper] order submitted: orderId={} symbol={}", order.getId(), order.getSymbol());
        broadcastOrderEvent(order, "NEW");
    }

    private static final int MAX_CAS_RETRIES = 3;

    @Override
    public void cancel(Order order) {
        // order 已经被 TradingService 推进到 PENDING_CANCEL。Paper 直接转 CANCELLED。
        // CAS 重试：防止并发 onTicker partial fill 导致 CAS 失败、订单卡 PENDING_CANCEL。
        for (int attempt = 1; attempt <= MAX_CAS_RETRIES; attempt++) {
            Order latest = orderMapper.findById(order.getId());
            if (latest == null) return;
            try {
                String prevStatus =
                        latest.getStatus() != null ? latest.getStatus().name() : null;
                latest.transitionTo(OrderStatus.CANCELLED);
                int affected = orderMapper.casUpdate(latest);
                if (affected == 1) {
                    latest.setVersion(latest.getVersion() + 1);
                    activeOrders.remove(latest.getId());
                    log.info("[paper] order cancelled: orderId={}", latest.getId());
                    broadcastOrderEvent(latest, prevStatus);
                    return; // 成功
                }
                log.debug(
                        "[paper] cancel CAS conflict: orderId={} attempt={}/{}",
                        order.getId(),
                        attempt,
                        MAX_CAS_RETRIES);
            } catch (IllegalOrderStateTransitionException e) {
                // 已被其他线程推到终态（如 FILLED），无需再撤
                log.info(
                        "[paper] cancel skipped (already terminal): orderId={} status={}",
                        order.getId(),
                        latest.getStatus());
                activeOrders.remove(order.getId());
                return;
            }
        }
        log.error(
                "[paper] cancel exhausted {} retries: orderId={} — keeping in activeOrders for self-healing",
                MAX_CAS_RETRIES,
                order.getId());
        // 不从 activeOrders 移除：CAS 持续失败意味着并发 fill 正在推进该订单，
        // 后续 onTicker 会在订单到达终态时自动 remove。移除反而导致订单卡 PENDING_CANCEL 无法自愈。
    }

    /**
     * Ticker 推送回调。遍历该 symbol 的所有活跃订单，调 MatchingKernel.match → 处理成交回报。
     *
     * <p>注意：matching 失败 / orderbook 不可用等异常不应阻断其他订单处理。
     */
    void onTicker(Ticker ticker) {
        MarketSnapshot snap = MarketSnapshot.fromTicker(ticker);
        // 收集需要移除/更新的 orderId，迭代结束后批量操作（避免并发修改问题）
        java.util.List<Long> toRemove = new java.util.ArrayList<>();
        java.util.Map<Long, Order> toUpdate = new java.util.HashMap<>();

        for (Order order : activeOrders.values()) {
            if (!ticker.symbol().equals(order.getSymbol())) continue;
            // 按下单账户选定的参考交易所过滤 ticker 来源，避免多交易所配置下同 symbol 重复/串价撮合
            // (order.exchange 由 TradingService.submit 按 account.getExchange() 设置)。
            if (!order.getExchange().equals(ticker.exchange())) continue;
            if (order.getStatus() == null || order.getStatus().isTerminal()) {
                toRemove.add(order.getId());
                continue;
            }
            // PENDING_CANCEL：撤单正在进行中(TradingService.cancel 已经/正在释放冻结额)，
            // Paper 场景撮合完全在本地模拟，没有"交易所已经先成交"的现实约束，不需要像 Live
            // 那样容忍撤单期间的成交——直接跳过撮合，避免撮合后 applyFill 对一笔已经解冻过的
            // 订单再解冻一次，把 paper_balances.used 冻出负数(凭空多出可用余额)。
            // 但如果 cancel CAS 耗尽后订单仍为 PENDING_CANCEL（自愈路径），需要检查是否已被
            // 其他线程推到终态（如 GTD expire），若是则从 activeOrders 移除。
            if (order.getStatus() == OrderStatus.PENDING_CANCEL) {
                Order latest = orderMapper.findById(order.getId());
                if (latest != null
                        && latest.getStatus() != null
                        && latest.getStatus().isTerminal()) {
                    toRemove.add(order.getId());
                }
                continue;
            }
            try {
                Optional<com.kwikquant.trading.domain.Fill> matched = MatchingKernel.match(order, snap, matchConfig);
                if (matched.isPresent()) {
                    var fill = matched.get();
                    executionService.processExecutionReport(new ExecutionReport(
                            order.getId(),
                            fill.getExternalFillId(),
                            fill.getPrice(),
                            fill.getQty(),
                            fill.getFee(),
                            fill.getFeeCurrency(),
                            fill.getLiquidity(),
                            fill.getFilledAt()));
                    // 重读 order 检查是否已 terminal（可能 FILLED）
                    Order reloaded = orderMapper.findById(order.getId());
                    if (reloaded != null) {
                        if (reloaded.getStatus() != null && reloaded.getStatus().isTerminal()) {
                            toRemove.add(order.getId());
                        } else {
                            toUpdate.put(order.getId(), reloaded);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn(
                        "[paper] match/processExecutionReport error: orderId={} error={}",
                        order.getId(),
                        e.getMessage());
            }
        }
        // 批量操作
        toRemove.forEach(activeOrders::remove);
        toUpdate.forEach(activeOrders::put);
    }

    /** ApplicationReady 时调用，从 DB 加载活跃 paper 订单到内存。仅 Step 7 启动恢复用。 */
    public void bootstrapActivePaperOrders(long accountId) {
        var actives = orderMapper.findActiveByAccount(accountId);
        for (Order o : actives) {
            activeOrders.put(o.getId(), o);
        }
        if (!actives.isEmpty()) {
            log.info("[paper] bootstrap loaded {} active orders for account {}", actives.size(), accountId);
        }
    }

    int activeOrderCount() {
        return activeOrders.size();
    }

    /** 重置模拟盘账户时清内存活跃订单池(按 accountId)。 */
    @Override
    public void clearActiveOrdersByAccount(long accountId) {
        activeOrders.entrySet().removeIf(e -> e.getValue().getAccountId() == accountId);
    }

    private void broadcastOrderEvent(Order order, String prevStatus) {
        try {
            long userId = accountService.findById(order.getAccountId()).getUserId();
            wsBroadcaster.broadcast(
                    userId,
                    OrderEvent.statusChanged(
                            order.getId(),
                            order.getAccountId(),
                            prevStatus,
                            order.getStatus() != null ? order.getStatus().name() : null,
                            order.getVersion()));
        } catch (RuntimeException e) {
            log.warn("[paper] WS broadcast failed: orderId={} error={}", order.getId(), e.getMessage());
        }
    }
}
