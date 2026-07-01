package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.risk.application.RiskService;
import com.kwikquant.risk.domain.RiskCheckRequest;
import com.kwikquant.risk.domain.RiskDecision;
import com.kwikquant.risk.domain.RiskRejectedException;
import com.kwikquant.risk.domain.RiskVerdict;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.AccountId;
import com.kwikquant.shared.types.OrderId;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.RiskTriggeredEvent;
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
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final MarketDataService marketDataService;
    private final AuditRepository auditRepository;
    private final ApplicationEventPublisher publisher;
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
            MarketDataService marketDataService,
            AuditRepository auditRepository,
            ApplicationEventPublisher publisher,
            MeterRegistry meterRegistry) {
        this.exchangeAccountService = exchangeAccountService;
        this.tradingPairService = tradingPairService;
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.positionMapper = positionMapper;
        this.orderRouter = orderRouter;
        this.riskService = riskService;
        this.marketDataService = marketDataService;
        this.auditRepository = auditRepository;
        this.publisher = publisher;
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

        TradingPairInfo pairInfo = findPair(account, cmd);
        Order order = Order.create(cmd, pairInfo);

        // INSERT status=NEW (independent transaction)
        insertOrder(order);

        // --- RiskGate integration ---
        BigDecimal notional = computeNotional(order, account, cmd);
        // ORDER_FREQUENCY input: count orders this account submitted in the last 60s.
        // Includes the current order (just inserted above) — frequency limit counts the
        // submitting order itself, so maxPerMinute=N allows N orders per minute.
        int recentOrderCount = (int) orderMapper.countByAccountSince(
                order.getAccountId(), Instant.now().minusSeconds(60));
        BigDecimal dailyPnl = fillMapper.sumNetCashflow(
                order.getAccountId(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS));
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
                order.transitionTo(OrderStatus.REJECTED);
                int affected = orderMapper.casUpdate(order);
                if (affected != 1) {
                    // Concurrent transition; re-read and return the latest state instead of
                    // throwing — the order may no longer be REJECTED.
                    Order latest = orderMapper.findById(order.getId());
                    ordersRejectedCounter.increment();
                    return OrderSubmitResult.from(latest, cmd);
                }
                ordersRejectedCounter.increment();
                throw new RiskRejectedException(order.getId(), "risk service unavailable");
            }
        }

        if (decision != null && decision.getVerdict() == RiskVerdict.REJECTED) {
            order.transitionTo(OrderStatus.REJECTED);
            int affected = orderMapper.casUpdate(order);
            if (affected != 1) {
                // Concurrent transition: another thread already moved this order's state.
                // Re-read and return the latest state; do not publish a rejection event since
                // the order may no longer be REJECTED.
                Order latest = orderMapper.findById(order.getId());
                ordersRejectedCounter.increment();
                return OrderSubmitResult.from(latest, cmd);
            }
            // M6: rejectionSummary carries only rule type names (no thresholds) — safe for WS push.
            publisher.publishEvent(new RiskTriggeredEvent(
                    currentUserId,
                    new OrderId(order.getId()),
                    new AccountId(order.getAccountId()),
                    null,
                    decision.rejectionSummary(),
                    Instant.now()));
            ordersRejectedCounter.increment();
            // Rule rejection is a business result encoded as HTTP 200 + code=4105
            // (ORDER_RISK_REJECTED) via RiskExceptionHandler — consistent with the
            // service-unavailable rejection path so the frontend uses one code for all
            // risk rejections (tech-design §3.3 scenario 2).
            throw new RiskRejectedException(order.getId(), decision.rejectionSummary());
        }
        // --- End RiskGate ---

        // 路由 + 异步提交（Executor 内部状态推进，不在此处事务）
        Executor executor = orderRouter.route(account);
        try {
            executor.submit(order);
            ordersSubmittedCounter.increment();
        } catch (RuntimeException e) {
            log.error("[trading] executor.submit failed: orderId={} error={}", order.getId(), e.getMessage(), e);
            ordersRejectedCounter.increment();
            throw e;
        }

        return OrderSubmitResult.from(order, cmd);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void insertOrder(Order order) {
        orderMapper.insert(order);
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
        order.transitionTo(OrderStatus.PENDING_CANCEL);
        int affected = orderMapper.casUpdate(order);
        if (affected != 1) {
            // 并发更新 → 重读后状态可能已变化，返回最新
            order = orderMapper.findById(orderId);
            return OrderCancelResult.from(order);
        }
        order.setVersion(order.getVersion() + 1);

        Executor executor = orderRouter.route(account);
        try {
            executor.cancel(order);
        } catch (RuntimeException e) {
            log.error("[trading] executor.cancel failed: orderId={} error={}", orderId, e.getMessage(), e);
            // 不抛: 状态已 PENDING_CANCEL，等 Executor 后续处理
        }

        return OrderCancelResult.from(order);
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
     * Computes the estimated notional value for risk checks.
     *
     * <p>Uses the order's limit price if available, otherwise falls back to the latest
     * market ticker. Returns null if no price information is available.
     */
    private BigDecimal computeNotional(Order order, ExchangeAccount account, OrderSubmitCommand cmd) {
        BigDecimal price = order.getPrice();
        if (price == null) {
            Ticker ticker =
                    marketDataService.getLatestTicker(account.getExchange(), cmd.marketType(), order.getSymbol());
            price = (ticker != null) ? ticker.last() : null;
        }
        return (price != null) ? order.getAmount().multiply(price) : null;
    }

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

    private TradingPairInfo findPair(ExchangeAccount account, OrderSubmitCommand cmd) {
        return tradingPairService.getPairs(account.getExchange(), cmd.marketType()).stream()
                .filter(p -> p.symbol().equals(cmd.symbol()))
                .findFirst()
                .orElseThrow(() ->
                        new InvalidOrderException("Unknown symbol on " + account.getExchange() + ": " + cmd.symbol()));
    }
}
