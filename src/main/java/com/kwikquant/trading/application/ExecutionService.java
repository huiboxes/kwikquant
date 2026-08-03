package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.application.FillCommand;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.AccountId;
import com.kwikquant.shared.types.LiquidationEvent;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderId;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.shared.types.OrderStatusChangedEvent;
import com.kwikquant.shared.types.Symbol;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.IllegalOrderStateTransitionException;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import com.kwikquant.trading.interfaces.FillEvent;
import com.kwikquant.trading.interfaces.OrderEvent;
import com.kwikquant.trading.interfaces.OrderWebSocketBroadcaster;
import com.kwikquant.trading.interfaces.PositionDto;
import com.kwikquant.trading.interfaces.PositionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 成交回报处理服务。成交入账事务边界的物理载体。
 *
 * <p>{@link #processExecutionReport(ExecutionReport)} 是核心方法：幂等 + CAS + 同事务原子写 Fill + Position。
 *
 * <p>Live 模式辅助方法：{@link #onExchangeAccepted}、{@link #onExchangeRejected} 推进 NEW → PENDING_NEW → SUBMITTED/REJECTED。
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final OrderMapper orderMapper;
    private final FillMapper fillMapper;
    private final PositionService positionService;
    private final OrderWebSocketBroadcaster wsBroadcaster;
    private final ExchangeAccountService accountService;
    private final com.kwikquant.account.application.BalanceService balanceService;
    private final AuditRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final LiquidationService liquidationService;
    private final Counter fillsCounter;
    private final Counter casConflictCounter;

    @Autowired
    public ExecutionService(
            OrderMapper orderMapper,
            FillMapper fillMapper,
            PositionService positionService,
            OrderWebSocketBroadcaster wsBroadcaster,
            ExchangeAccountService accountService,
            MeterRegistry meterRegistry,
            com.kwikquant.account.application.BalanceService balanceService,
            AuditRepository auditRepository,
            ApplicationEventPublisher eventPublisher,
            LiquidationService liquidationService) {
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.positionService = positionService;
        this.wsBroadcaster = wsBroadcaster;
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.auditRepository = auditRepository;
        this.eventPublisher = eventPublisher;
        this.liquidationService = liquidationService;
        this.fillsCounter = Counter.builder("trading.fills")
                .description("Total fills processed")
                .register(meterRegistry);
        this.casConflictCounter = Counter.builder("trading.cas.conflict")
                .tag("table", "orders")
                .description("CAS conflict count")
                .register(meterRegistry);
    }

    /**
     * 处理成交回报。幂等（按 externalFillId）+ CAS + 同事务写 Fill + Position。
     * CAS 冲突重试 {@value TradingConstants#MAX_CAS_RETRIES} 次。
     *
     * <p><b>事务隔离: READ_COMMITTED</b>（Postgres 默认）。CAS 重试循环依赖此隔离级别——每次
     * {@code orderMapper.findById} 必须读到最新已提交版本，重试才有意义。若改为 REPEATABLE_READ，
     * 同一事务内所有 SELECT 读同一快照，CAS 重读永远拿到旧 version，3 次重试全部无效后抛
     * ConcurrencyConflictException。<b>修改隔离级别前必须同步审查 CAS 逻辑。</b>
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void processExecutionReport(ExecutionReport report) {
        Order order = orderMapper.findById(report.orderId());
        if (order == null) {
            throw new OrderNotFoundException(report.orderId());
        }

        // 幂等：按 (accountId, externalFillId) 去重
        if (report.externalFillId() == null) {
            log.warn("[execution] externalFillId is null for orderId={}, idempotency check skipped", report.orderId());
        } else if (fillMapper.existsByExternalFillId(order.getAccountId(), report.externalFillId())) {
            log.debug(
                    "[execution] idempotent skip: orderId={} externalFillId={}",
                    report.orderId(),
                    report.externalFillId());
            return;
        }

        // 终态 fill：写审计日志 + 略过（fill-after-terminal 竞态）
        if (order.getStatus() != null && order.getStatus().isTerminal()) {
            log.warn(
                    "[execution] fill received after terminal status: orderId={} status={} externalFillId={}",
                    order.getId(),
                    order.getStatus(),
                    report.externalFillId());
            return;
        }

        // CAS 重试循环
        for (int attempt = 0; attempt < TradingConstants.MAX_CAS_RETRIES; attempt++) {
            // MEDIUM #4 fix: 每次重试都重检 terminal（防止 cancel/GTD expire 在重试期间推到终态，
            // 导致 fill-after-terminal 绕过保护、双重解冻）
            if (order.getStatus() != null && order.getStatus().isTerminal()) {
                log.warn(
                        "[execution] fill skipped in retry: orderId={} already terminal status={} externalFillId={}",
                        order.getId(),
                        order.getStatus(),
                        report.externalFillId());
                return;
            }

            // 每次重试都重新检查幂等（防止 WS 重发 + CAS 冲突同时发生）
            if (report.externalFillId() != null
                    && fillMapper.existsByExternalFillId(order.getAccountId(), report.externalFillId())) {
                log.debug(
                        "[execution] idempotent skip in retry: orderId={} externalFillId={}",
                        report.orderId(),
                        report.externalFillId());
                return;
            }

            // accumulateFill 是纯内存计算（filledQty + weighted avg price），不涉及状态机
            try {
                order.accumulateFill(report.qty(), report.price());
            } catch (com.kwikquant.trading.domain.MatchingException e) {
                // over-fill: 累计成交量超过订单总量 → 撮合 bug，不应发生
                log.error(
                        "[execution] over-fill detected: orderId={} status={} error={}",
                        order.getId(),
                        order.getStatus(),
                        e.getMessage());
                return;
            }

            // 尝试状态推进
            final OrderStatus nextStatus =
                    order.remainingQty().signum() == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            boolean statusChanged = true;
            // R2:保存 transition 前真实 prevStatus。原 bug 在 transitionTo 之后取 order.getStatus(),
            // 此时 status 已被 transitionTo 改成 nextStatus,导致 prevStatus=newStatus,
            // OrderEvent/OrderStatusChangedEvent 的 prevStatus 字段一直错。
            final OrderStatus prevStatusBeforeTransition = order.getStatus();
            try {
                order.transitionTo(nextStatus);
            } catch (IllegalOrderStateTransitionException e) {
                // 竞态：order 在 CAS 重试期间被其他线程推进（典型场景：cancel → PENDING_CANCEL）。
                //
                // 关键原则：fill 是交易所真实成交记录，无论 order 当前状态如何都必须持久化。
                // 状态转换失败只意味着"不能按预期推进状态"，不意味着"这笔成交不存在"。
                //
                // 处理方式：保持 order 当前状态不变（如 PENDING_CANCEL），但仍写入
                // filledQty/filledAvgPrice + fill 记录 + position 更新。等 cancel 结果回来后再
                // 由 onExchangeAccepted/cancel 回调决定最终态。startupSnapshot 对账兜底。
                statusChanged = false;
                log.warn(
                        "[execution] fill persisted without status change due to concurrent state: "
                                + "orderId={} currentStatus={} attemptedStatus={} externalFillId={} qty={} price={}",
                        order.getId(),
                        e.from(),
                        nextStatus,
                        report.externalFillId(),
                        report.qty(),
                        report.price());
            }

            int affected = orderMapper.casUpdate(order);
            if (affected == 1) {
                order.setVersion(order.getVersion() + 1);
                // 持久化 Fill
                Fill fill = Fill.create(
                        order.getId(),
                        order.getAccountId(),
                        order.getSymbol(),
                        order.getSide(),
                        report.price(),
                        report.qty(),
                        report.fee() == null ? BigDecimal.ZERO : report.fee(),
                        report.feeCurrency(),
                        report.liquidity(),
                        report.externalFillId(),
                        report.filledAt());
                try {
                    fillMapper.insert(fill);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // 幂等兜底：TOCTOU 间隙内另一线程已插入同一 externalFillId，DB 唯一约束拦截。
                    log.debug(
                            "[execution] idempotent skip (DB constraint): orderId={} externalFillId={}",
                            order.getId(),
                            report.externalFillId());
                    return;
                }
                fillsCounter.increment();
                // 应用持仓:SPOT 走 6 参数重载;PERP 走 10 参数(含 MarketType/positionEffect/leverage/marginMode),
                // 否则 PERP 成交被当 SPOT 处理 → 误更新 SPOT 持仓 qty 而非创建 PERP 持仓。
                // PERP 重载返 realizedPnlDelta(平仓 PnL),供下方 applyPnlSettlement 入账。
                BigDecimal realizedPnlDelta = BigDecimal.ZERO;
                if (order.getMarketType() == MarketType.PERP) {
                    realizedPnlDelta = positionService.applyFill(
                            order.getAccountId(),
                            order.getSymbol(),
                            order.getSide(),
                            report.qty(),
                            report.price(),
                            fill.getFee(),
                            MarketType.PERP,
                            order.getPositionEffect(),
                            order.getLeverage(),
                            order.getMarginMode());
                } else {
                    realizedPnlDelta = positionService.applyFill(
                            order.getAccountId(),
                            order.getSymbol(),
                            order.getSide(),
                            report.qty(),
                            report.price(),
                            fill.getFee());
                }
                // 回填 fill 的 realized_pnl_delta(开仓=0;平仓=PnL)。applyFill 必须在 insert 后
                // (幂等+并发一致性,见 PositionService.applyFill 注释),故用 UPDATE 回填而非 insert
                // 时写入。供 DAILY_LOSS_LIMIT 按日汇总真实已实现 PnL(旧 sumNetCashflow 口径把开仓
                // BUY 支出当亏损误拦,把 PERP OPEN_SHORT side=SELL 当收入虚高漏拦)。
                fill.setRealizedPnlDelta(realizedPnlDelta);
                fillMapper.updateRealizedPnlDelta(fill.getId(), realizedPnlDelta);

                // 应用余额(模拟盘真实扣减/入账;真实交易所 noop)。同事务 REQUIRED(无
                // @Transactional 标注 = 加入 processExecutionReport 事务),保证余额扣减 + 持仓 +
                // 订单推进 + Fill insert 原子。复用 account 查询给 WS userId,避免额外 DB 调用。
                // FillCommand 传 marketType/positionEffect:PERP 走保证金分支(开仓释放保证金/扣 fee,
                // 平仓 noop),SPOT 传 SPOT/null 沿用 SPOT 逻辑(HIGH-1:旧 null,null 致 PERP 成交走
                // SPOT 逻辑,扣 full notional 非 margin、凭空造 base、PnL 不入账)。
                ExchangeAccount acct = accountService.findById(order.getAccountId());
                if (acct != null) {
                    // BUY partial fill: 按本次成交量占订单总量比例计算应解冻的 frozenQuoteAmount，
                    // 避免每次 fill 都释放整单冻结额导致 used 被多减。最后一笔用减法兜底消除尾差。
                    BigDecimal proportionalFrozen = Order.computeProportionalFrozen(
                            order.getFrozenQuoteAmount(), report.qty(), order.getAmount());
                    balanceService.applyFill(new FillCommand(
                            order.getAccountId(),
                            acct.isPaperTrading(),
                            order.getSide(),
                            order.getSymbol(),
                            report.qty(),
                            report.price(),
                            fill.getFee(),
                            proportionalFrozen,
                            order.getMarketType(),
                            order.getPositionEffect()));
                    // PERP 平仓 PnL 入账(对齐 processLiquidation applyLiquidationDelta 口径;
                    // applyFill 对 CLOSE_* noop,PnL 全靠此结算,无双重计账)。
                    if (order.getMarketType() == MarketType.PERP && realizedPnlDelta.signum() != 0) {
                        balanceService.applyPnlSettlement(
                                order.getAccountId(),
                                acct.isPaperTrading(),
                                Symbol.splitQuoteCurrency(order.getSymbol()),
                                realizedPnlDelta);
                    }
                }

                // 事务提交后推送 WS 事件（避免客户端在事务提交前收到消息查到旧数据）
                long userId = acct != null ? acct.getUserId() : 0L;
                final long orderIdForWs = order.getId();
                final long accountIdForWs = order.getAccountId();
                final long versionForWs = order.getVersion();
                final Fill fillForWs = fill;
                final String symbolForWs = order.getSymbol();
                final boolean didStatusChange = statusChanged;
                final OrderStatus effectiveNextStatus = nextStatus;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // 仅在状态实际变更时推送 OrderEvent + publish OrderStatusChangedEvent
                        if (didStatusChange) {
                            wsBroadcaster.broadcast(
                                    userId,
                                    OrderEvent.statusChanged(
                                            orderIdForWs,
                                            accountIdForWs,
                                            prevStatusBeforeTransition,
                                            effectiveNextStatus,
                                            versionForWs));
                            // R2 修复:publish 领域事件,驱动 OrderActivityListener(实时动态 ORDER_FILLED)
                            // 和 NotificationService(成交通知)。原代码只 broadcast WS 不 publishEvent,
                            // 导致两监听者永不触发(audit_logs 无 ORDER_FILLED,无成交通知)。
                            eventPublisher.publishEvent(new OrderStatusChangedEvent(
                                    userId,
                                    new OrderId(orderIdForWs),
                                    new AccountId(accountIdForWs),
                                    prevStatusBeforeTransition,
                                    effectiveNextStatus,
                                    Instant.now()));
                        }
                        wsBroadcaster.broadcast(userId, FillEvent.of(toFillDto(fillForWs)));
                        // 推送 PositionEvent — 重读最新持仓状态
                        broadcastPositionUpdate(userId, accountIdForWs, symbolForWs);
                    }
                });
                return;
            }

            // CAS 冲突 → 重读
            casConflictCounter.increment();
            order = orderMapper.findById(report.orderId());
            if (order == null) {
                throw new OrderNotFoundException(report.orderId());
            }
        }

        throw new ConcurrencyConflictException(
                "Order CAS failed after " + TradingConstants.MAX_CAS_RETRIES + " retries: orderId=" + report.orderId());
    }

    /**
     * 强平处理(逐仓 PERP,五步事务)。
     *
     * <p>由 {@code PaperExecutor.onTicker} 在 markPrice 跌破 liquidationPrice 时调用。
     * 五步同事务(@Transactional REQUIRED, READ_COMMITTED):
     * <ol>
     *   <li>{@link PositionService#applyFill}(PERP, CLOSE_*, leverage, marginMode) → realizedPnlDelta
     *       (含 CAS 重试 3 次;全平后 qty=0/side=flat/frozenAmount=0/liquidationPrice=null)</li>
     *   <li>{@link com.kwikquant.account.application.BalanceService#applyLiquidationDelta}
     *       (dFree=dTotal=realizedPnlDelta, 内部 clamp 0 兜底负余额保护)。不调 unfreeze——
     *       开仓成交时 applyPerpFill 已把 frozenQuoteAmount 从 used 释放回 free(used=0),
     *       强平时无保证金可释放,只需 PnL 结算(开仓成交时 applyPerpFill 已把 frozenQuoteAmount
     *       从 used 释放回 free,强平时 used=0)</li>
     *   <li>{@link OrderMapper#insert}(系统强平 Order,status=FILLED 绕过 validate + 状态机,
     *       {@link Order#createLiquidation} 工厂构造)</li>
     *   <li>{@link FillMapper#insert}(Fill,externalFillId="liq-{positionId}-{millis}",
     *       orderId=系统 Order.id 满足 NOT NULL 约束)</li>
     *   <li>{@link AuditRepository#save}(AuditEntry action=LIQUIDATION targetType=POSITION 同事务)+
     *       afterCommit {@link #eventPublisher}.publishEvent({@link LiquidationEvent})</li>
     * </ol>
     *
     * <p><b>CAS 冲突回滚</b>:applyFill(步骤 1)抛 {@link ConcurrencyConflictException} → 事务回滚,
     * 后续步骤全不执行,audit 也不记(同事务回滚)。调用方(PaperExecutor.onTicker)catch 后下 tick
     * 再判(强平幂等:position 仍在,markPrice 仍跌破,再触发)。
     *
     * @param positionId      被强平的持仓 ID
     * @param markPrice        触发强平的标记价(也是强平成交价)
     * @param triggerOrderId   触发强平的订单 ID(可空,纯 markPrice 跌破无触发订单时为 null;
     *                         传入则记入 audit + LiquidationEvent.orderId)
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void processLiquidation(long positionId, BigDecimal markPrice, Long triggerOrderId) {
        liquidationService.processLiquidation(positionId, markPrice, triggerOrderId);
    }

    /** Live 模式：交易所接受订单。NEW → PENDING_NEW → SUBMITTED。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onExchangeAccepted(long orderId, String exchangeOrderId) {
        Order order = requireOrder(orderId);
        OrderStatus prevStatus = order.getStatus();
        if (order.getStatus() == OrderStatus.NEW) {
            casTransition(order, OrderStatus.PENDING_NEW, exchangeOrderId);
        }
        if (order.getStatus() == OrderStatus.PENDING_NEW) {
            casTransition(order, OrderStatus.SUBMITTED, exchangeOrderId);
        }
        broadcastStatusChange(order, prevStatus);
    }

    /** Live 模式：交易所拒绝订单。NEW → PENDING_NEW → REJECTED。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void onExchangeRejected(long orderId, String reason) {
        Order order = requireOrder(orderId);
        OrderStatus prevStatus = order.getStatus();
        if (order.getStatus() == OrderStatus.NEW) {
            casTransition(order, OrderStatus.PENDING_NEW, null);
        }
        casTransition(order, OrderStatus.REJECTED, null);
        log.warn("[execution] order rejected by exchange: orderId={} reason={}", orderId, reason);
        broadcastStatusChange(order, prevStatus);
    }

    private void casTransition(Order order, OrderStatus target, String exchangeOrderId) {
        order.transitionTo(target);
        if (exchangeOrderId != null) {
            order.setExchangeOrderId(exchangeOrderId);
        }
        int affected = orderMapper.casUpdate(order);
        if (affected != 1) {
            throw new ConcurrencyConflictException(
                    "CAS update failed on transition to " + target + " for order " + order.getId());
        }
        order.setVersion(order.getVersion() + 1);
    }

    /** 事务提交后推送订单状态变更事件。 */
    private void broadcastStatusChange(Order order, OrderStatus prevStatus) {
        long userId = resolveUserId(order.getAccountId());
        final long orderId = order.getId();
        final long accountId = order.getAccountId();
        final OrderStatus newStatus = order.getStatus();
        final long version = order.getVersion();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wsBroadcaster.broadcast(
                        userId, OrderEvent.statusChanged(orderId, accountId, prevStatus, newStatus, version));
                // R2 修复:Live 模式状态变更(NEW→SUBMITTED / →REJECTED)同样 publish,
                // 驱动 OrderActivityListener/NotificationService(后者只对 FILLED/CANCELLED 通知)。
                eventPublisher.publishEvent(new OrderStatusChangedEvent(
                        userId, new OrderId(orderId), new AccountId(accountId), prevStatus, newStatus, Instant.now()));
            }
        });
    }

    private Order requireOrder(long orderId) {
        Order order = orderMapper.findById(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    /** 通过 accountId 查找 userId（WS 推送需要）。缓存或批量场景可优化。 */
    private long resolveUserId(long accountId) {
        // ExchangeAccountService.findById 不检查 ownership，内部调用安全
        var account = accountService.findById(accountId);
        return account != null ? account.getUserId() : 0L;
    }

    /**
     * 事务提交后推送持仓更新事件。重读最新持仓状态确保推送的是已提交数据。
     */
    private void broadcastPositionUpdate(long userId, long accountId, String symbol) {
        try {
            // 该 symbol 所有持仓(SPOT + PERP 双向)逐个推 PositionEvent 触发前端 invalidate。
            // 旧 findByAccountAndSymbol 单行查询在 SPOT+PERP 同 symbol 时报 TooManyResultsException,
            // 导致 PositionEvent 不推、前端持仓不刷新(需手动刷新页面才看到 PERP 持仓)。
            List<Position> positions = positionService.findByAccount(accountId);
            int pushed = 0;
            for (Position pos : positions) {
                if (symbol.equals(pos.getSymbol())) {
                    wsBroadcaster.broadcast(userId, PositionEvent.of(toPositionDto(pos)));
                    pushed++;
                }
            }
            log.info(
                    "[ws] broadcastPositionUpdate: userId={} accountId={} symbol={} total={} pushed={}",
                    userId,
                    accountId,
                    symbol,
                    positions.size(),
                    pushed);
        } catch (Exception e) {
            log.warn("[ws] failed to broadcast PositionEvent: accountId={} symbol={}", accountId, symbol, e);
        }
    }

    private PositionDto toPositionDto(com.kwikquant.trading.domain.Position pos) {
        return new PositionDto(
                pos.getId(),
                pos.getAccountId(),
                pos.getSymbol(),
                pos.getSide(),
                pos.getQty(),
                pos.getAvgEntryPrice(),
                pos.getRealizedPnl(),
                null,
                null,
                pos.getVersion(),
                pos.getLeverage(),
                pos.getMarginMode() != null ? pos.getMarginMode().name() : null,
                pos.getPositionSide(),
                pos.getLiquidationPrice(),
                pos.getMaintMargin(),
                pos.getFrozenAmount(),
                pos.getUpdatedAt());
    }

    private com.kwikquant.trading.interfaces.FillDto toFillDto(Fill fill) {
        return new com.kwikquant.trading.interfaces.FillDto(
                fill.getId(),
                fill.getOrderId(),
                fill.getAccountId(),
                fill.getSymbol(),
                fill.getSide() != null ? fill.getSide().name().toLowerCase() : null,
                fill.getPrice(),
                fill.getQty(),
                fill.getFee(),
                fill.getFeeCurrency(),
                fill.getLiquidity(),
                fill.getExternalFillId(),
                fill.getFilledAt());
    }
}
