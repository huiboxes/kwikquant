package com.kwikquant.trading.application;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.risk.application.RiskService;
import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.infra.Auditable;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.AccountId;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.OrderId;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.RiskTriggeredEvent;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.IllegalOrderStateTransitionException;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.infrastructure.PositionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下单入口 + 撤单入口 + 订单查询。Trading 模块对外的唯一 application 入口。
 *
 * <p>{@link #submit(OrderSubmitCommand)} 流程：(1) 鉴权（user 是否拥有 account） → (2) 校验 + 创建 Order → (3) INSERT
 * status=NEW → (4) RiskGate 风控检查 → (5) 路由到 Executor → (6) 同步返回 OrderSubmitResult。
 * Executor.submit 异步推进状态。
 *
 * <p>RiskGate 在 Order.create 之后、Executor.submit 之前注入。风控拒绝 → CAS NEW→REJECTED +
 * 发布 RiskTriggeredEvent + 返回 rejected 结果。风控服务不可用时，平仓单 bypass（审计记录），
 * 开仓单直接拒绝。
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final ExchangeAccountService exchangeAccountService;
    private final TradingPairService tradingPairService;
    private final OrderMapper orderMapper;
    private final FillMapper fillMapper;
    private final PositionMapper positionMapper;
    private final OrderRouter orderRouter;
    private final RiskService riskService;
    private final AuditRepository auditRepository;
    private final ApplicationEventPublisher publisher;
    private final BalanceService balanceService;
    private final TradingTransactionHelper txHelper;
    private final OrderMetricsService orderMetrics;
    private final Counter ordersSubmittedCounter;
    private final Counter ordersRejectedCounter;

    @Autowired
    public TradingService(
            ExchangeAccountService exchangeAccountService,
            TradingPairService tradingPairService,
            OrderMapper orderMapper,
            FillMapper fillMapper,
            PositionMapper positionMapper,
            OrderRouter orderRouter,
            RiskService riskService,
            AuditRepository auditRepository,
            ApplicationEventPublisher publisher,
            MeterRegistry meterRegistry,
            BalanceService balanceService,
            TradingTransactionHelper txHelper,
            OrderMetricsService orderMetrics) {
        this.exchangeAccountService = exchangeAccountService;
        this.tradingPairService = tradingPairService;
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.positionMapper = positionMapper;
        this.orderRouter = orderRouter;
        this.riskService = riskService;
        this.auditRepository = auditRepository;
        this.publisher = publisher;
        this.balanceService = balanceService;
        this.txHelper = txHelper;
        this.orderMetrics = orderMetrics;
        this.ordersSubmittedCounter = Counter.builder("trading.orders.submitted")
                .description("Total orders submitted")
                .register(meterRegistry);
        this.ordersRejectedCounter = Counter.builder("trading.orders.rejected")
                .description("Total orders rejected")
                .register(meterRegistry);
    }

    /**
     * 下单入口。鉴权 → 校验 → 创建 Order → INSERT status=NEW → RiskGate → 路由到 Executor → 返回结果。
     *
     * <p>Order INSERT 是独立小事务（避免长事务）。Executor.submit 异步执行，不在事务内。
     */
    public OrderSubmitResult submit(OrderSubmitCommand cmd) {
        long currentUserId = SecurityUtils.currentUserId();
        ExchangeAccount account = loadOwnedAccount(cmd.accountId(), currentUserId);

        TradingPairInfo pairInfo = findPair(account.getExchange(), cmd);
        Order order = Order.create(cmd, pairInfo);
        order.setExchange(account.getExchange());

        // INSERT status=NEW (independent transaction)
        txHelper.insertOrder(order);

        // --- RiskGate integration ---
        // MARKET BUY 取价 + notional + 近 60s 下单数 + 当日盈亏 抽到 OrderMetricsService，
        // 让风控预检端点（POST /api/v1/risk/dry-run）复用同一计算路径，保证 verdict faithful（无漂移）。
        // TD-013：marketPrice 仍同时供下方 freezeBalance 共用，消除重复查询。
        BigDecimal marketPrice = orderMetrics.resolveMarketPrice(
                account, order.getSide(), order.getSymbol(), cmd.marketType(), order.getPrice());
        // MARKET BUY 必须有有效价格，否则 notional 为 null 会绕过风控额度检查、freezeBalance fallback
        // 重新查价可能拿到不同价格导致风控与冻结不一致。fail-fast 避免风控逃逸。
        if (marketPrice == null && order.getOrderType() == OrderType.MARKET && order.getSide() == OrderSide.BUY) {
            return rejectOrder(
                    order,
                    cmd,
                    new InvalidOrderException("MARKET BUY requires valid ticker price, but none available"),
                    null);
        }
        BigDecimal notional = orderMetrics.notional(order.getAmount(), order.getPrice(), marketPrice);
        int recentOrderCount = orderMetrics.countRecentOrders(order.getAccountId());
        BigDecimal dailyPnl = orderMetrics.dailyRealizedPnl(order.getAccountId());
        RiskCheckRequest riskRequest = new RiskCheckRequest(
                order.getId(),
                order.getAccountId(),
                currentUserId,
                order.getSymbol(),
                order.getSide(),
                order.getOrderType(),
                order.getAmount(),
                order.getPrice(),
                notional,
                recentOrderCount,
                dailyPnl,
                UUID.randomUUID().toString());

        RiskDecision decision;
        try {
            decision = riskService.check(riskRequest);
        } catch (RuntimeException e) {
            if (isPositionReducing(order)) {
                log.warn("[risk] bypassed for position-reducing order: orderId={}", order.getId(), e);
                try {
                    auditRepository.save(new AuditEntry(
                            String.valueOf(currentUserId),
                            "RISK_BYPASSED",
                            "ORDER",
                            String.valueOf(order.getId()),
                            MDC.get("traceId"),
                            "SUCCESS",
                            null,
                            Map.of("reason", "risk service unavailable"),
                            Instant.now()));
                } catch (RuntimeException auditEx) {
                    log.error(
                            "[risk] RISK_BYPASSED audit save failed, order will proceed: orderId={}",
                            order.getId(),
                            auditEx);
                }
                decision = null; // bypassed
            } else {
                return rejectOrder(
                        order, cmd, new RiskRejectedException(order.getId(), "risk service unavailable"), null);
            }
        }

        if (decision != null && decision.getVerdict() == RiskVerdict.REJECTED) {
            // M6: rejectionSummary carries only rule type names (no thresholds) — safe for WS push.
            // Rule rejection is a business result encoded as HTTP 200 + code=4105
            // (ORDER_RISK_REJECTED) via RiskExceptionHandler — consistent with the
            // service-unavailable rejection path so the frontend uses one code for all
            // risk rejections (tech-design §3.3 scenario 2).
            final RiskDecision rejectedDecision = decision;
            return rejectOrder(
                    order,
                    cmd,
                    new RiskRejectedException(order.getId(), rejectedDecision.rejectionSummary()),
                    () -> publisher.publishEvent(new RiskTriggeredEvent(
                            currentUserId,
                            new OrderId(order.getId()),
                            new AccountId(order.getAccountId()),
                            null,
                            rejectedDecision.rejectionSummary(),
                            Instant.now())));
        }
        // --- End RiskGate ---

        // --- 余额冻结(RiskGate 后,executor 前;模拟盘真实冻结,真实交易所 noop) ---
        // 余额不足 → CAS NEW→REJECTED + 重新抛出(走 TradingExceptionHandler → 4102 ORDER_INSUFFICIENT_BALANCE)
        // ResourceStateConflictException: freeze CAS 耗尽(高并发同账户下单),reject 订单避免孤儿 NEW
        try {
            txHelper.freezeBalance(order, account, marketPrice);
        } catch (InsufficientBalanceException | InvalidOrderException e) {
            return rejectOrder(order, cmd, e, null);
        } catch (com.kwikquant.shared.infra.ResourceStateConflictException e) {
            log.warn(
                    "[trading] freeze CAS exhausted, rejecting order: orderId={} error={}",
                    order.getId(),
                    e.getMessage());
            return rejectOrder(
                    order, cmd, new InvalidOrderException("concurrent order submission conflict, please retry"), null);
        }

        // 路由 + 异步提交（Executor 内部状态推进，不在此处事务）
        Executor executor = orderRouter.route(account);
        try {
            executor.submit(order);
            ordersSubmittedCounter.increment();
        } catch (RuntimeException e) {
            log.error("[trading] executor.submit failed: orderId={} error={}", order.getId(), e.getMessage(), e);
            ordersRejectedCounter.increment();
            // 补偿解冻:executor 失败未成交,释放冻结额(非模拟盘 noop)
            try {
                txHelper.unfreezeBalance(order, account);
            } catch (RuntimeException ex) {
                log.warn("[trading] compensatory unfreeze failed: orderId={}", order.getId(), ex);
            }
            throw e;
        }

        return OrderSubmitResult.from(order, cmd);
    }

    /**
     * CAS 转 REJECTED。并发冲突（另一线程已推进状态）时不抛异常，重读最新状态直接返回；
     * 成功转移则跑一次可选的 {@code onRejectedSuccess}（如发布 {@link RiskTriggeredEvent}），
     * 计数后抛 {@code cause}——交由 {@code RiskExceptionHandler}/{@code TradingExceptionHandler}
     * 映射成业务响应（HTTP 200/422 + 具体错误码，而非裸 500）。三处拒单路径（风控服务不可用/风控拒绝/
     * 余额不足）共用这个方法，避免各自重复一遍 CAS + 计数 + 并发冲突处理。
     */
    private OrderSubmitResult rejectOrder(
            Order order, OrderSubmitCommand cmd, RuntimeException cause, Runnable onRejectedSuccess) {
        order.transitionTo(OrderStatus.REJECTED);
        int affected = orderMapper.casUpdate(order);
        if (affected != 1) {
            Order latest = orderMapper.findById(order.getId());
            ordersRejectedCounter.increment();
            return OrderSubmitResult.from(latest, cmd);
        }
        if (onRejectedSuccess != null) {
            onRejectedSuccess.run();
        }
        ordersRejectedCounter.increment();
        throw cause;
    }

    /**
     * 撤单入口。鉴权 → 推进 status → PENDING_CANCEL → 转发到 Executor.cancel 异步处理。
     *
     * <p>Executor.cancel 在 Paper 是直接转 CANCELLED；在 Live 是调 CCXT cancelOrder 后由 WS 回报确认。
     */
    public OrderCancelResult cancel(long orderId) {
        long currentUserId = SecurityUtils.currentUserId();
        Order order = orderMapper.findById(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        // 统一返回 404 避免存在性探测
        ExchangeAccount account;
        try {
            account = exchangeAccountService.getOwned(order.getAccountId(), currentUserId);
        } catch (RuntimeException e) {
            throw new OrderNotFoundException(orderId);
        }

        // 状态机校验（终态等无法撤）
        try {
            order.transitionTo(OrderStatus.PENDING_CANCEL);
        } catch (IllegalOrderStateTransitionException e) {
            if (order.getStatus() != null && order.getStatus().isTerminal()) {
                // 订单已在终态（如 GTD expire/cancel-fill 竞态），重读最新状态返回
                log.info(
                        "[trading] cancel rejected (already terminal): orderId={} status={}",
                        orderId,
                        order.getStatus());
                order = orderMapper.findById(orderId);
                return OrderCancelResult.from(order);
            }
            // NEW 等非终态但不允许 → PENDING_CANCEL 的状态，明确拒绝
            log.warn(
                    "[trading] cancel rejected (invalid state transition): orderId={} from={} error={}",
                    orderId,
                    order.getStatus(),
                    e.getMessage());
            return OrderCancelResult.from(order);
        }
        int affected = orderMapper.casUpdate(order);
        if (affected != 1) {
            // 并发更新 → 重读后状态可能已变化，返回最新
            order = orderMapper.findById(orderId);
            return OrderCancelResult.from(order);
        }
        order.setVersion(order.getVersion() + 1);

        // 先让 Executor 撤单(PaperExecutor 会把该订单从内存活跃订单池里摘掉),再解冻余额——
        // 顺序不能反：如果先解冻再摘除，中间有一个窗口期，activeOrders 里还留着这笔订单的
        // 旧引用(status 仍是内存里的 SUBMITTED，因为这里操作的是从 DB 重新读出的另一个 Order
        // 对象，不会同步到 PaperExecutor 内存池里的那个引用)，这段时间恰好来一个 ticker
        // 就会把已经解冻的这笔订单撮合成交，导致 applyFill 再解冻一次——since 已经解冻过，
        // 会把 used 冻结出负数，凭空多出一段可用余额。先摘池子再解冻，把这个竞态窗口从
        // "一次跨事务 DB 调用的耗时"收窄到"从内存 map 里摘除的那一刻"。
        Executor executor = orderRouter.route(account);
        try {
            executor.cancel(order);
        } catch (RuntimeException e) {
            log.error("[trading] executor.cancel failed: orderId={} error={}", orderId, e.getMessage(), e);
            // 不抛: 状态已 PENDING_CANCEL，等 Executor 后续处理
        }

        // 解冻剩余冻结额(已成交部分由 applyFill 在成交时解冻;cancel 只处理未成交)。
        // 非模拟盘 noop。重新读 DB 状态：如果 cancel 窗口期内被 onTicker 撮合成交（FILLED），
        // applyFill 已经释放了冻结量，此处不再重复解冻。
        // HIGH #1 fix: 必须用 latest order 做 unfreeze，因为传入的 order 是 cancel 开始时的快照，
        // remainingQty 可能已过时（并发 partial fill 后 filledQty 增加、remainingQty 减少）。
        // 用旧快照 unfreeze SELL 单会多释放 base 冻结量 → 余额凭空增多。
        try {
            Order latest = orderMapper.findById(orderId);
            if (latest != null
                    && latest.getStatus() != null
                    && latest.getStatus().isTerminal()
                    && latest.getStatus() != OrderStatus.PENDING_CANCEL) {
                log.info("[trading] cancel skip unfreeze: orderId={} already {}", orderId, latest.getStatus());
            } else if (latest != null) {
                txHelper.unfreezeBalance(latest, account);
            }
        } catch (RuntimeException e) {
            log.warn("[trading] cancel unfreeze failed: orderId={}", orderId, e);
        }

        return OrderCancelResult.from(order);
    }

    /**
     * 重置模拟盘账户:取消活跃订单 + 清持仓 + 余额回 10 万 USDT。仅模拟盘账户,非模拟盘抛
     * IllegalArgumentException。@Auditable(PAPER_RESET) + 单事务原子(订单取消 + 持仓删除 + 余额重置)。
     *
     * <p>订单用批量 SQL 绕状态机(重置是强制清空,无需逐个 CAS);余额 reset 由 BalanceService
     * 委托 paperBalanceAdapter(deleteByAccount + 重插 10万 USDT)。
     */
    @Transactional
    @Auditable(action = "PAPER_RESET", targetType = "exchange_account", targetId = "#accountId")
    public void resetPaperAccount(long accountId, long userId) {
        ExchangeAccount account = exchangeAccountService.getOwned(accountId, userId);
        if (!account.isPaperTrading()) {
            throw new IllegalArgumentException("reset only supported for paper accounts, accountId=" + accountId);
        }
        // 1. 批量取消活跃订单(DB)
        orderMapper.cancelAllActiveByAccount(accountId);
        // 2. 清 PaperExecutor 内存活跃订单池(避免已 CANCELLED 订单仍被 onTicker 撮合)
        Executor executor = orderRouter.route(account);
        executor.clearActiveOrdersByAccount(accountId);
        // 3. 删持仓
        positionMapper.deleteByAccount(accountId);
        // 4. 余额重置(清 paper_balances + 重插 10 万 USDT)
        balanceService.reset(accountId, true);
    }

    public Order getOrder(long orderId) {
        long currentUserId = SecurityUtils.currentUserId();
        Order order = orderMapper.findById(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        // 统一返回 404 避免存在性探测（不区分 "不存在" 和 "不属于你"）
        loadOwnedAccountSilent(order.getAccountId(), currentUserId, orderId);
        return order;
    }

    /**
     * 查账户未终结挂单（Wave 10 MCP {@code get_open_orders} 用）。薄查询转发 {@link
     * com.kwikquant.trading.infrastructure.OrderMapper#findActiveByAccount}（SQL {@code WHERE account_id = ?
     * AND status NOT IN ('FILLED','CANCELLED','REJECTED','EXPIRED')}，语义=未终结=open orders）。
     *
     * <p><b>所有权校验在调用方</b>：MCP 工具层（{@code TradingTools.getOpenOrders}）前置
     * {@code ExchangeAccountService.getOwned} 校验 accountId 属当前用户；本方法本身不校验（与
     * {@link PositionService#findByAccount} 同风格，userId 由调用方保证）。
     */
    public List<Order> listOpenByAccount(long accountId) {
        return orderMapper.findActiveByAccount(accountId);
    }

    // ── R3-03 薄查询：report 模块经此访问 trading 数据，不直连 OrderMapper/FillMapper（模块边界）──
    // 与 listOpenByAccount 同风格：所有权校验在调用方（report 的 resolveAccountIds 已校验 account 属用户）。

    /** 按条件查询订单（report TradeHistoryService.query/stats 用）。转发 OrderMapper.findByQuery。 */
    public List<Order> queryOrders(
            long accountId,
            String symbol,
            List<OrderStatus> statuses,
            Instant startTime,
            Instant endTime,
            int limit,
            int offset) {
        return orderMapper.findByQuery(accountId, symbol, statuses, startTime, endTime, limit, offset);
    }

    /** 按条件计数订单（report TradeHistoryService.query 用）。转发 OrderMapper.countByQuery。 */
    public long countOrders(
            long accountId, String symbol, List<OrderStatus> statuses, Instant startTime, Instant endTime) {
        return orderMapper.countByQuery(accountId, symbol, statuses, startTime, endTime);
    }

    /** 查订单的成交列表（report TradeHistoryService 用）。转发 FillMapper.findByOrderId。 */
    public List<Fill> listFillsByOrder(long orderId) {
        return fillMapper.findByOrderId(orderId);
    }

    /** 批量查多个订单的成交列表（消除 N+1）。转发 FillMapper.findByOrderIds。 */
    public List<Fill> listFillsByOrders(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        return fillMapper.findByOrderIds(orderIds);
    }

    /** 按账户汇总成交量和手续费（替代 Java 层 N+1 循环）。转发 FillMapper.sumVolumeAndFees。 */
    public FillMapper.VolumeAndFees sumVolumeAndFees(long accountId, Instant since) {
        return fillMapper.sumVolumeAndFees(accountId, since);
    }

    /** 汇总账户净现金流（report TradeHistoryService.stats 用，realizedPnl 计算）。转发 FillMapper.sumNetCashflow。 */
    public BigDecimal sumNetCashflow(long accountId, Instant since) {
        return fillMapper.sumNetCashflow(accountId, since);
    }

    /**
     * Computes the estimated notional value for risk checks.
     *
     * <p>Uses the order's limit price if available, otherwise uses the pre-fetched marketPrice.
     * Returns null if no price information is available.
     */
    /**
     * Determines if an order is position-reducing (eligible for risk bypass on service failure).
     *
     * <p>Only SELL-side stop/take-profit/trailing orders are considered position-reducing.
     */
    private boolean isPositionReducing(Order order) {
        boolean isProtectiveType =
                switch (order.getOrderType()) {
                    case STOP_MARKET, STOP_LIMIT, TAKE_PROFIT_MARKET, TAKE_PROFIT_LIMIT, TRAILING_STOP -> true;
                    default -> false;
                };
        if (!isProtectiveType) {
            return false;
        }
        Position pos = positionMapper.findByAccountAndSymbol(order.getAccountId(), order.getSymbol());
        if (pos == null || pos.isFlat()) {
            return false;
        }
        // long position reduces via SELL; short position reduces via BUY
        return (Position.SIDE_LONG.equals(pos.getSide()) && order.getSide() == OrderSide.SELL)
                || (Position.SIDE_SHORT.equals(pos.getSide()) && order.getSide() == OrderSide.BUY);
    }

    private ExchangeAccount loadOwnedAccount(long accountId, long userId) {
        try {
            return exchangeAccountService.getOwned(accountId, userId);
        } catch (RuntimeException e) {
            throw new AccessDeniedException("account not accessible: " + accountId);
        }
    }

    /** 鉴权失败时抛 OrderNotFoundException 而非 AccessDeniedException，防止 orderId 存在性探测。 */
    private void loadOwnedAccountSilent(long accountId, long userId, long orderId) {
        try {
            exchangeAccountService.getOwned(accountId, userId);
        } catch (RuntimeException e) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private TradingPairInfo findPair(Exchange exchange, OrderSubmitCommand cmd) {
        return tradingPairService.getPairs(exchange, cmd.marketType()).stream()
                .filter(p -> p.symbol().equals(cmd.symbol()))
                .findFirst()
                .orElseThrow(() -> new InvalidOrderException("Unknown symbol on " + exchange + ": " + cmd.symbol()));
    }
}
