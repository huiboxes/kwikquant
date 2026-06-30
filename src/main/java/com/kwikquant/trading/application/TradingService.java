package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.InvalidOrderException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.OrderSubmitCommand;
import com.kwikquant.trading.infrastructure.OrderMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下单入口 + 撤单入口 + 订单查询。Trading 模块对外的唯一 application 入口。
 *
 * <p>{@link #submit(OrderSubmitCommand)} 流程：(1) 鉴权（user 是否拥有 account） → (2) 校验 + 创建 Order → (3) INSERT
 * status=NEW → (4) 路由到 Executor → (5) 同步返回 OrderSubmitResult。Executor.submit 异步推进状态。
 *
 * <p>Wave 5 RiskGate 在 Order.create 之后、Executor.submit 之前注入（见 §3.7 关键决策点）。
 */
@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final ExchangeAccountService exchangeAccountService;
    private final TradingPairService tradingPairService;
    private final OrderMapper orderMapper;
    private final OrderRouter orderRouter;
    private final Counter ordersSubmittedCounter;
    private final Counter ordersRejectedCounter;

    @Autowired
    public TradingService(
            ExchangeAccountService exchangeAccountService,
            TradingPairService tradingPairService,
            OrderMapper orderMapper,
            OrderRouter orderRouter,
            MeterRegistry meterRegistry) {
        this.exchangeAccountService = exchangeAccountService;
        this.tradingPairService = tradingPairService;
        this.orderMapper = orderMapper;
        this.orderRouter = orderRouter;
        this.ordersSubmittedCounter = Counter.builder("trading.orders.submitted")
                .description("Total orders submitted")
                .register(meterRegistry);
        this.ordersRejectedCounter = Counter.builder("trading.orders.rejected")
                .description("Total orders rejected")
                .register(meterRegistry);
    }

    /**
     * 下单入口。鉴权 → 校验 → 创建 Order → INSERT status=NEW → 路由到 Executor → 返回 PENDING_NEW。
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

        // Wave 5: RiskGate hook here, see wave4-tech-design §3.7
        // RiskGate.check(order, account) — 失败则 CAS NEW→REJECTED + 抛 RiskRejectedException

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
