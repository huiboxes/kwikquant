package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.IllegalOrderStateTransitionException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.OrderMapper;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Live trading 模式 Executor。CCXT 真撮合：submit → createOrder + onExchangeAccepted/Rejected；cancel →
 * cancelOrder + WS confirmation；fill push → ExecutionService.processExecutionReport。
 *
 * <p>per-account WS 订阅在 {@link #ensureWsSubscription(ExchangeAccount)} 中按需建立（首次 submit 或 startupSnapshot
 * 时）。WS 断连重连容错由 Wave 4 Step 7 完整接入。
 *
 * <p>CCXT 真实集成由 {@link CcxtOrderAdapter} 接口隔离，spike S1/S2 验证前由 DefaultCcxtOrderAdapter 占位（抛
 * UnsupportedOperationException）。
 */
@Component
public class LiveExecutor implements Executor {

    private static final Logger log = LoggerFactory.getLogger(LiveExecutor.class);

    private final CcxtOrderAdapter ccxtAdapter;
    private final ExchangeAccountService accountService;
    private final ExecutionService executionService;
    private final OrderMapper orderMapper;

    /** per-account WS 订阅取消句柄；防止重复订阅。 */
    private final ConcurrentMap<Long, Runnable> wsSubscriptions = new ConcurrentHashMap<>();

    /**
     * per (account, symbol, posSide) 缓存最近一次成功设到交易所的 leverage/marginMode(4a.5)。
     *
     * <p>OKX 双向持仓 leverage/marginMode 是 per posSide(long/short 各自),故 key 含 posSide。
     * submit 前 order 字段对比缓存,变更才调 setLeverage/setMarginMode(避免每单重复调 OKX API 限频)。
     * CLOSE_* 的 leverage/marginMode 从 Position 派生(Order.from Position),与持仓一致不触发重复调。
     */
    private final ConcurrentMap<LeverageCacheKey, LeverageMarginState> leverageCache = new ConcurrentHashMap<>();

    @Autowired
    public LiveExecutor(
            CcxtOrderAdapter ccxtAdapter,
            ExchangeAccountService accountService,
            ExecutionService executionService,
            OrderMapper orderMapper) {
        this.ccxtAdapter = ccxtAdapter;
        this.accountService = accountService;
        this.executionService = executionService;
        this.orderMapper = orderMapper;
    }

    @PostConstruct
    void init() {
        log.info("[live] LiveExecutor initialized; WS subscriptions established lazily on first submit");
    }

    @Override
    public void submit(Order order) {
        ExchangeAccount account = loadAccountSilently(order.getAccountId());
        if (account == null) {
            log.error("[live] cannot find account {} for order {}", order.getAccountId(), order.getId());
            return;
        }
        try {
            ensureLeverageMarginMode(
                    account, order); // 4a.5: submit 前 per (account,symbol,posSide) 缓存调 setLeverage/setMarginMode
            String exchangeOrderId = ccxtAdapter.createOrder(account, order);
            executionService.onExchangeAccepted(order.getId(), exchangeOrderId);
            ensureWsSubscription(account);
        } catch (ExchangeException e) {
            log.warn("[live] order rejected by exchange: orderId={} reason={}", order.getId(), e.getMessage());
            executionService.onExchangeRejected(order.getId(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("[live] unexpected error submitting order {}: {}", order.getId(), e.getMessage(), e);
            executionService.onExchangeRejected(order.getId(), e.getMessage());
        }
    }

    @Override
    public void cancel(Order order) {
        ExchangeAccount account = loadAccountSilently(order.getAccountId());
        if (account == null) return;
        try {
            ccxtAdapter.cancelOrder(account, order);
            // WS 推送会确认 cancel 完成（推进到 CANCELLED）。这里只发起请求。
        } catch (RuntimeException e) {
            log.warn("[live] cancel error: orderId={} error={}", order.getId(), e.getMessage());
        }
    }

    /** 启动恢复 / WS 重连后调用：从交易所拉快照对账本地状态。 */
    public void startupSnapshot(ExchangeAccount account) {
        try {
            CcxtOrderAdapter.AccountSnapshot snap = ccxtAdapter.fetchSnapshot(account);
            log.info(
                    "[live] startup snapshot for account {}: {} open orders, {} positions",
                    account.getId(),
                    snap.openOrders().size(),
                    snap.positions().size());
            // 简化对账：用 exchange_order_id 回查本地，若本地有则不做改动；本地无则记审计（人工介入）。
            // Wave 5+ RiskGate 接入后再做精细对账。
            for (CcxtOrderAdapter.OrderSnapshot o : snap.openOrders()) {
                Order local = orderMapper.findByExchangeOrderId(account.getId(), o.exchangeOrderId());
                if (local == null) {
                    log.warn(
                            "[live] startup found unknown order on exchange: accountId={} exchangeOrderId={} symbol={}",
                            account.getId(),
                            o.exchangeOrderId(),
                            o.symbol());
                }
            }
            ensureWsSubscription(account);
        } catch (RuntimeException e) {
            log.error("[live] startupSnapshot failed for account {}: {}", account.getId(), e.getMessage(), e);
        }
    }

    /**
     * submit 前 per (account, symbol, posSide) 缓存对比,变更才调 setLeverage/setMarginMode(4a.5)。
     *
     * <p>SPOT(positionEffect=null → posSide=null)或缺 leverage/marginMode 跳过(SPOT 无杠杆/保证金模式)。
     * setLeverage/setMarginMode 失败抛 {@link ExchangeException} 冒到 submit catch → onExchangeRejected
     * (没设成功杠杆不该下单);缓存只在两调用都成功后 put,失败不缓存下次重试。
     */
    private void ensureLeverageMarginMode(ExchangeAccount account, Order order) {
        PositionSide posSide = PositionSide.from(order.getPositionEffect());
        if (posSide == null || order.getLeverage() == null || order.getMarginMode() == null) {
            return;
        }
        int leverage = order.getLeverage();
        MarginMode mode = order.getMarginMode();
        LeverageCacheKey key = new LeverageCacheKey(account.getId(), order.getSymbol(), posSide);
        LeverageMarginState last = leverageCache.get(key);
        boolean changed = false;
        if (last == null || last.leverage() != leverage) {
            ccxtAdapter.setLeverage(account, order.getSymbol(), order.getMarketType(), leverage, mode, posSide);
            changed = true;
        }
        if (last == null || last.marginMode() != mode) {
            ccxtAdapter.setMarginMode(account, order.getSymbol(), order.getMarketType(), mode, leverage, posSide);
            changed = true;
        }
        if (changed) {
            leverageCache.put(key, new LeverageMarginState(leverage, mode));
        }
    }

    /** leverage/marginMode 缓存 key:per (account, symbol, posSide)。OKX 双向 per posSide 各自。 */
    private record LeverageCacheKey(long accountId, String symbol, PositionSide posSide) {}

    /** 缓存值:最近一次成功设到交易所的 leverage/marginMode。 */
    private record LeverageMarginState(int leverage, MarginMode marginMode) {}

    private void ensureWsSubscription(ExchangeAccount account) {
        wsSubscriptions.computeIfAbsent(
                account.getId(),
                id -> ccxtAdapter.subscribeFills(account, event -> {
                    try {
                        executionService.processExecutionReport(new ExecutionReport(
                                event.orderId(),
                                event.externalFillId(),
                                event.price(),
                                event.qty(),
                                event.fee(),
                                event.feeCurrency(),
                                event.liquidity(),
                                event.filledAt()));
                    } catch (RuntimeException e) {
                        log.warn(
                                "[live] WS fill processing failed: orderId={} externalFillId={} error={}",
                                event.orderId(),
                                event.externalFillId(),
                                e.getMessage());
                    }
                }));
    }

    /**
     * 推进 status → CANCELLED （WS 回报触发或外部调用）。CAS 失败时重试最多
     * {@value TradingConstants#MAX_CAS_RETRIES} 次，防止并发 fill/cancel 竞态导致撤单确认丢失、
     * 订单永远停在 PENDING_CANCEL。
     */
    public void confirmCancelled(long orderId) {
        for (int attempt = 1; attempt <= TradingConstants.MAX_CAS_RETRIES; attempt++) {
            Order order = orderMapper.findById(orderId);
            if (order == null) return;
            try {
                order.transitionTo(OrderStatus.CANCELLED);
                int affected = orderMapper.casUpdate(order);
                if (affected == 1) {
                    order.setVersion(order.getVersion() + 1);
                    return; // 成功
                }
                // CAS 失败：另一线程已修改该订单，重读后重试
                log.debug(
                        "[live] confirmCancelled CAS conflict: orderId={} attempt={}/{}",
                        orderId,
                        attempt,
                        TradingConstants.MAX_CAS_RETRIES);
            } catch (IllegalOrderStateTransitionException e) {
                // 状态机拒绝转换（如已是终态），无需重试
                log.info(
                        "[live] confirmCancelled skipped (already terminal): orderId={} error={}",
                        orderId,
                        e.getMessage());
                return;
            } catch (RuntimeException e) {
                log.warn(
                        "[live] confirmCancelled transient error: orderId={} attempt={} error={}",
                        orderId,
                        attempt,
                        e.getMessage());
                // 瞬态 DB 故障不立即放弃，继续重试
            }
        }
        log.error(
                "[live] confirmCancelled exhausted {} retries, order may be stuck in PENDING_CANCEL: orderId={}",
                TradingConstants.MAX_CAS_RETRIES,
                orderId);
    }

    private ExchangeAccount loadAccountSilently(long accountId) {
        // Live 模式由 executor 内部回调触发（WS fill/accepted push），无 SecurityContext。
        // 使用 ExchangeAccountService.findById（无 ownership 检查）加载账户；ownership 已在
        // TradingService.submit 入口由 getOwned(accountId, currentUserId) 校验过，此处为内部后续操作。
        try {
            return accountService.findById(accountId);
        } catch (RuntimeException e) {
            log.warn("[live] failed to load account {}: {}", accountId, e.getMessage());
            return null;
        }
    }
}
