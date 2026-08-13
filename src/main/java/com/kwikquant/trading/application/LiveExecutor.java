package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.BillType;
import com.kwikquant.trading.domain.IllegalOrderStateTransitionException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderAlreadyTerminalException;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.OkxOrderTranslator;
import com.kwikquant.trading.infrastructure.OrderMapper;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
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
 * 时）。WS 断连重连容错已完整接入。
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
    private final LiquidationService liquidationService;
    private final FundingSettlementService fundingSettlementService;

    /** per-account WS 订阅取消句柄；防止重复订阅。 */
    private final ConcurrentMap<Long, Runnable> wsSubscriptions = new ConcurrentHashMap<>();

    /** per-account bills 订阅取消句柄(实盘强平/资金费率/ADL 同步);防止重复订阅。 */
    private final ConcurrentMap<Long, Runnable> billsSubscriptions = new ConcurrentHashMap<>();

    /**
     * per (account, symbol, posSide) 缓存最近一次成功设到交易所的 leverage/marginMode。
     *
     * <p>OKX 双向持仓 leverage/marginMode 是 per posSide(long/short 各自),故 key 含 posSide。
     * submit 前 order 字段对比缓存,变更才调 setLeverage/setMarginMode(避免每单重复调 OKX API 限频)。
     * CLOSE_* 的 leverage/marginMode 从 Position 派生(Order.from Position),与持仓一致不触发重复调。
     */
    private final ConcurrentMap<LeverageCacheKey, LeverageMarginState> leverageCache = new ConcurrentHashMap<>();

    /** per-account 持仓模式(双向)已设缓存。首次 PERP 下单调 setPositionMode(59000 幂等),记缓存避免重复调。 */
    private final ConcurrentMap<Long, Boolean> positionModeSet = new ConcurrentHashMap<>();

    @Autowired
    public LiveExecutor(
            CcxtOrderAdapter ccxtAdapter,
            ExchangeAccountService accountService,
            ExecutionService executionService,
            OrderMapper orderMapper,
            LiquidationService liquidationService,
            FundingSettlementService fundingSettlementService) {
        this.ccxtAdapter = ccxtAdapter;
        this.accountService = accountService;
        this.executionService = executionService;
        this.orderMapper = orderMapper;
        this.liquidationService = liquidationService;
        this.fundingSettlementService = fundingSettlementService;
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
        boolean orderRequestStarted = false;
        try {
            ensurePositionMode(account); // 首次 PERP 设双向持仓模式(幂等 59000)
            ensureLeverageMarginMode(
                    account, order); // submit 前 per (account,symbol,posSide) 缓存调 setLeverage/setMarginMode
            executionService.onExchangeSubmitting(order.getId());
            orderRequestStarted = true;
            String exchangeOrderId = ccxtAdapter.createOrder(account, order);
            executionService.onExchangeAccepted(order.getId(), exchangeOrderId);
            ensureWsSubscription(account);
            ensureBillsSubscription(account); // 实盘强平/资金费率/ADL 同步
        } catch (ExchangeException e) {
            if (e.isRetryable() && orderRequestStarted) {
                log.error(
                        "[live] order result unknown; retaining PENDING_NEW for reconciliation: orderId={} reason={}",
                        order.getId(),
                        e.getMessage());
                ensureWsSubscription(account);
            } else {
                log.warn(
                        "[live] order not sent or explicitly rejected: orderId={} reason={}",
                        order.getId(),
                        e.getMessage());
                executionService.onExchangeRejected(order.getId(), e.getMessage());
            }
        } catch (RuntimeException e) {
            if (orderRequestStarted) {
                log.error(
                        "[live] order result unknown; retaining PENDING_NEW for reconciliation: orderId={} reason={}",
                        order.getId(),
                        e.getMessage(),
                        e);
                ensureWsSubscription(account);
            } else {
                log.warn(
                        "[live] order preparation failed before send: orderId={} reason={}",
                        order.getId(),
                        e.getMessage());
                executionService.onExchangeRejected(order.getId(), e.getMessage());
            }
        }
    }

    @Override
    public void cancel(Order order) {
        ExchangeAccount account = loadAccountSilently(order.getAccountId());
        if (account == null) return;
        try {
            ccxtAdapter.cancelOrder(account, order);
            // cancelOrder 是同步 REST(OKX),成功即交易所已撤。经结算守卫推进 PENDING_CANCEL→CANCELLED:
            // 若交易所累计成交尚未落库(撤单瞬间的在途成交),等 fill poller 补齐后由周期对账终态,
            // 防提前落 CANCELLED 把真实成交挡在门外。旧:依赖未接线的 WS 推送确认,order 永卡
            // PENDING_CANCEL,前端永远"撤单中"(broadcastStatusChange 只在 accepted/rejected 调,cancel 不调)。
            Order fresh = orderMapper.findById(order.getId());
            if (fresh != null) {
                reconcileOrder(account, fresh, false);
            }
        } catch (OrderAlreadyTerminalException e) {
            // 51400 同时覆盖已成交、已撤销和不存在，必须查询最终订单，不能猜成 CANCELLED。
            log.info("[live] cancel returned terminal/unknown; querying final state: orderId={}", order.getId());
            reconcileOrder(account, order, false);
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
            Set<String> openOnExchange = new HashSet<>();
            for (CcxtOrderAdapter.OrderSnapshot o : snap.openOrders()) {
                if (o.exchangeOrderId() != null) {
                    openOnExchange.add(o.exchangeOrderId());
                }
                Order local = orderMapper.findByExchangeOrderId(account.getId(), o.exchangeOrderId());
                if (local == null && o.clientOrderId() != null) {
                    local = findByOkxClientOrderId(account.getId(), o.clientOrderId());
                    if (local != null) {
                        executionService.onExchangeAccepted(local.getId(), o.exchangeOrderId());
                    }
                }
                if (local == null) {
                    log.warn(
                            "[live] startup found unknown order on exchange: accountId={} exchangeOrderId={} symbol={}",
                            account.getId(),
                            o.exchangeOrderId(),
                            o.symbol());
                }
            }
            for (Order local : orderMapper.findActiveByAccount(account.getId())) {
                OrderStatus status = local.getStatus();
                if (status == OrderStatus.PENDING_NEW || status == OrderStatus.PENDING_CANCEL) {
                    reconcileOrder(account, local, true);
                } else if ((status == OrderStatus.SUBMITTED || status == OrderStatus.PARTIALLY_FILLED)
                        && local.getExchangeOrderId() != null
                        && !openOnExchange.contains(local.getExchangeOrderId())) {
                    // 本地活跃但交易所挂单列表已无此单(已撤/已全成/受理尚不可见):查最终状态,
                    // 防止撤单/成交回报遗漏导致订单永卡活跃态。
                    reconcileOrder(account, local, false);
                }
            }
            ensureWsSubscription(account);
            ensureBillsSubscription(account); // 启动恢复也起 bills 订阅
        } catch (RuntimeException e) {
            log.error("[live] startupSnapshot failed for account {}: {}", account.getId(), e.getMessage(), e);
        }
    }

    /** 启动及周期对账入口。 */
    public void reconcileAccount(ExchangeAccount account) {
        startupSnapshot(account);
    }

    private void reconcileOrder(ExchangeAccount account, Order order, boolean retryLiveCancel) {
        try {
            CcxtOrderAdapter.OrderSnapshot remote = ccxtAdapter.fetchOrder(account, order);
            if (remote == null) {
                log.warn(
                        "[live] order still unknown on exchange: orderId={} clOrdId={}",
                        order.getId(),
                        OkxOrderTranslator.clientOrderId(order));
                return;
            }
            boolean remoteCancelled = "canceled".equals(remote.status()) || "cancelled".equals(remote.status());
            boolean remoteFilled = "filled".equals(remote.status());

            if (order.getStatus() == OrderStatus.PENDING_NEW) {
                if (remoteCancelled) {
                    if (fillsSettled(order, remote)) {
                        confirmCancelled(order.getId());
                    } else {
                        // 交易所有未落库成交：先落 exchangeOrderId，等 fill poller 补齐成交；
                        // 订单离开 PENDING_NEW 后由"本地活跃但已不挂单"兜底对账完成终态。
                        executionService.onExchangeAccepted(order.getId(), remote.exchangeOrderId());
                        ensureWsSubscription(account);
                    }
                } else {
                    executionService.onExchangeAccepted(order.getId(), remote.exchangeOrderId());
                    if (remoteFilled) {
                        ensureWsSubscription(account);
                    }
                }
                return;
            }

            if (remoteCancelled) {
                if (fillsSettled(order, remote)) {
                    confirmCancelled(order.getId());
                } else {
                    // 结算守卫：交易所累计成交 > 本地已落成交时不落 CANCELLED，
                    // 等 fill poller 补完再终态，防提前终态把在途成交挡在门外。
                    log.warn(
                            "[live] exchange cancelled but fills unsettled; waiting for fill poller:"
                                    + " orderId={} remoteFilled={} localFilled={}",
                            order.getId(),
                            remote.filledQty(),
                            order.getFilledQty());
                    ensureWsSubscription(account);
                }
            } else if (remoteFilled) {
                // fill poller 必须先原子写 Fill/Position，再推进到 FILLED；不能抢先落终态。
                ensureWsSubscription(account);
            } else if (order.getStatus() == OrderStatus.PENDING_CANCEL && retryLiveCancel) {
                cancel(order);
            }
        } catch (RuntimeException e) {
            log.warn("[live] order reconciliation failed: orderId={} error={}", order.getId(), e.getMessage());
        }
    }

    /** 结算守卫：交易所累计成交 ≤ 本地已落成交才允许落终态，否则在途成交会被终态挡掉。 */
    private boolean fillsSettled(Order order, CcxtOrderAdapter.OrderSnapshot remote) {
        BigDecimal remoteFilled = remote.filledQty() == null ? BigDecimal.ZERO : remote.filledQty();
        BigDecimal localFilled = order.getFilledQty() == null ? BigDecimal.ZERO : order.getFilledQty();
        return remoteFilled.compareTo(localFilled) <= 0;
    }

    private Order findByOkxClientOrderId(long accountId, String clOrdId) {
        for (Order order : orderMapper.findActiveByAccount(accountId)) {
            if (OkxOrderTranslator.clientOrderId(order).equals(clOrdId)) {
                return order;
            }
        }
        return null;
    }

    /**
     * submit 前 per (account, symbol, posSide) 缓存对比,变更才调 setLeverage/setMarginMode。
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
    private void ensurePositionMode(ExchangeAccount account) {
        if (account.isPaperTrading()) return; // PAPER 不调交易所
        if (positionModeSet.putIfAbsent(account.getId(), Boolean.TRUE) != null) return; // 已设过跳过
        try {
            ccxtAdapter.setPositionMode(account);
        } catch (ExchangeException e) {
            positionModeSet.remove(account.getId()); // 失败移除,下次重试
            throw e;
        }
    }

    private record LeverageCacheKey(long accountId, String symbol, PositionSide posSide) {}

    /** 缓存值:最近一次成功设到交易所的 leverage/marginMode。 */
    private record LeverageMarginState(int leverage, MarginMode marginMode) {}

    private void ensureWsSubscription(ExchangeAccount account) {
        wsSubscriptions.computeIfAbsent(
                account.getId(),
                id -> ccxtAdapter.subscribeFills(
                        account,
                        event -> executionService.processExecutionReport(new ExecutionReport(
                                event.orderId(),
                                event.externalFillId(),
                                event.price(),
                                event.qty(),
                                event.fee(),
                                event.feeCurrency(),
                                event.liquidity(),
                                event.filledAt()))));
    }

    /**
     * per-account 启动 OKX bills 订阅(实盘强平/资金费率/ADL 同步)。
     *
     * <p>5s REST 轮询 /api/v5/account/bills(仿 {@link #ensureWsSubscription} 的 fills 订阅),
     * 按 {@link BillType} 分流(OKX type int → BillType 映射在 OkxOrderTranslator.parseBills):
     * <ul>
     *   <li>{@code LIQUIDATION}(OKX type=5)/{@code ADL}(type=9) → {@link LiquidationService#processLiquidationReport}</li>
     *   <li>{@code FUNDING}(type=8) → {@link FundingSettlementService#processFundingBill}</li>
     *   <li>{@code OTHER} 忽略(1 Transfer/2 Trade/...)</li>
     * </ul>
     * 仅实盘账户起(PaperExecutor.checkLiquidation 自处理模拟盘强平,PAPER 资金费率 8h @Scheduled 模拟)。
     */
    private void ensureBillsSubscription(ExchangeAccount account) {
        billsSubscriptions.computeIfAbsent(
                account.getId(),
                id -> ccxtAdapter.subscribeBills(account, bill -> {
                    BillType type = bill.type();
                    if (type == BillType.LIQUIDATION || type == BillType.ADL) {
                        liquidationService.processLiquidationReport(bill);
                    } else if (type == BillType.FUNDING) {
                        fundingSettlementService.processFundingBill(bill);
                    }
                    return true;
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
