package com.kwikquant.trading.application;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.LiquidationEvent;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.shared.types.Symbol;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderNotFoundException;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 强平服务(ExecutionService 大重构第一步:从 processLiquidation 380-507 拆出)。
 *
 * <p>五步事务:applyFill(PERP CLOSE_*) → applyLiquidationDelta → OrderMapper.insert(系统 Order)
 * → FillMapper.insert → AuditRepository.save + afterCommit publish LiquidationEvent。
 *
 * <p>依赖 positionService/accountService/balanceService/orderMapper/fillMapper/auditRepository/eventPublisher
 * (ExecutionService 字段子集,无 wsBroadcaster/meterRegistry)。
 */
@Service
public class LiquidationService {

    private final PositionService positionService;
    private final ExchangeAccountService accountService;
    private final BalanceService balanceService;
    private final OrderMapper orderMapper;
    private final FillMapper fillMapper;
    private final AuditRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;

    public LiquidationService(
            PositionService positionService,
            ExchangeAccountService accountService,
            BalanceService balanceService,
            OrderMapper orderMapper,
            FillMapper fillMapper,
            AuditRepository auditRepository,
            ApplicationEventPublisher eventPublisher) {
        this.positionService = positionService;
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.auditRepository = auditRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 强平处理(ExecutionService.processLiquidation 委托)。
     *
     * @param positionId      被强平的持仓 ID
     * @param markPrice        触发强平的标记价(也是强平成交价)
     * @param triggerOrderId   触发强平的订单 ID(可空,纯 markPrice 跌破无触发订单时为 null;
     *                         传入则记入 audit + LiquidationEvent.orderId)
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void processLiquidation(long positionId, BigDecimal markPrice, Long triggerOrderId) {
        Position position = positionService.findById(positionId);
        if (position == null) {
            throw new OrderNotFoundException(positionId);
        }
        // 派生平仓方向:LONG 持仓 → CLOSE_LONG(side=SELL),SHORT 持仓 → CLOSE_SHORT(side=BUY)
        // positionSide 是大写 "LONG"/"SHORT"(DB chk_positions_position_side 约束),与 side 字段
        // 小写 "long"/"short"(Position.SIDE_LONG)不同——按 positionSide 大写判(与 V31 索引/约束一致)。
        String posSide = position.getPositionSide();
        PositionEffect effect;
        OrderSide side;
        if ("LONG".equals(posSide)) {
            effect = PositionEffect.CLOSE_LONG;
            side = OrderSide.SELL;
        } else if ("SHORT".equals(posSide)) {
            effect = PositionEffect.CLOSE_SHORT;
            side = OrderSide.BUY;
        } else {
            throw new IllegalStateException("liquidation requires LONG/SHORT position, got positionSide=" + posSide
                    + " for positionId=" + positionId);
        }
        long accountId = position.getAccountId();
        String symbol = position.getSymbol();
        BigDecimal qty = position.getQty();
        String quoteCurrency = Symbol.splitQuoteCurrency(symbol);

        // 步骤 1:applyFill(PERP, CLOSE_*, leverage, marginMode) → realizedPnlDelta(含 CAS 重试)
        // 复用 PositionService.applyFill,不重复 CAS 逻辑。失败抛 ConcurrencyConflictException → 事务回滚。
        BigDecimal realizedPnlDelta = positionService.applyFill(
                accountId,
                symbol,
                side,
                qty,
                markPrice,
                BigDecimal.ZERO,
                MarketType.PERP,
                effect,
                position.getLeverage(),
                position.getMarginMode());

        ExchangeAccount acct = accountService.findById(accountId);
        boolean paper = acct != null && acct.isPaperTrading();
        Exchange exchange = acct != null ? acct.getExchange() : null;
        long userId = acct != null ? acct.getUserId() : 0L;

        // 步骤 2:applyLiquidationDelta(PnL 结算 + clamp 0)。不调 unfreeze(used 已在开仓成交时释放)
        balanceService.applyLiquidationDelta(accountId, paper, quoteCurrency, realizedPnlDelta, realizedPnlDelta);

        // 步骤 3:系统强平 Order insert(status=FILLED,绕过 validate + 状态机)
        Order sysOrder = Order.createLiquidation(position, exchange, markPrice, effect);
        orderMapper.insert(sysOrder);

        // 步骤 4:Fill insert(orderId=系统 Order.id 满足 NOT NULL;externalFillId 幂等键)
        String externalFillId = "liq-" + positionId + "-" + Instant.now().toEpochMilli();
        Fill fill = Fill.create(
                sysOrder.getId(),
                accountId,
                symbol,
                side,
                markPrice,
                qty,
                BigDecimal.ZERO,
                quoteCurrency,
                "taker",
                externalFillId,
                Instant.now());
        // 强平 fill 的 realized_pnl_delta = 步骤1 applyFill 返回的平仓 PnL。与 processExecutionReport
        // 不同:此处 applyFill(步骤1)在 fill insert(步骤4)之前,故 insert 时直接写入,无需 update 回填。
        fill.setRealizedPnlDelta(realizedPnlDelta);
        fillMapper.insert(fill);

        // 步骤 5a:audit_logs 同事务写(action=LIQUIDATION targetType=POSITION targetId=positionId)
        // metadata 不含 null value(AuditEntry Map.copyOf 不允许 null),triggerOrderId 可空时条件 put
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("positionId", positionId);
        metadata.put("systemOrderId", sysOrder.getId());
        if (triggerOrderId != null) {
            metadata.put("triggerOrderId", triggerOrderId);
        }
        metadata.put("qty", qty);
        metadata.put("liquidationPrice", position.getLiquidationPrice());
        metadata.put("markPrice", markPrice);
        metadata.put("realizedPnl", realizedPnlDelta);
        metadata.put("frozenAmount", position.getFrozenAmount());
        auditRepository.save(new AuditEntry(
                "system",
                "LIQUIDATION",
                "POSITION",
                String.valueOf(positionId),
                null,
                AuditEntry.STATUS_SUCCESS,
                null,
                metadata,
                Instant.now()));

        // 步骤 5b:afterCommit publishEvent(LiquidationEvent)——事务提交后才发,避免客户端收到事件查不到数据
        final long fUserId = userId;
        final long fAccountId = accountId;
        final long fPositionId = positionId;
        final String fPositionSide = posSide;
        final Integer fLeverage = position.getLeverage();
        final BigDecimal fLiqPrice = position.getLiquidationPrice();
        final BigDecimal fMarkPrice = markPrice;
        final BigDecimal fRealizedPnl = realizedPnlDelta;
        final BigDecimal fFrozen = position.getFrozenAmount() != null ? position.getFrozenAmount() : BigDecimal.ZERO;
        final Long fTriggerOrderId = triggerOrderId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // marginBalance 派生 = frozenAmount + realizedPnl(不查 PaperBalance 共享桶)
                BigDecimal marginBalance = fFrozen.add(fRealizedPnl);
                eventPublisher.publishEvent(new LiquidationEvent(
                        fUserId,
                        fTriggerOrderId,
                        fAccountId,
                        fPositionId,
                        fPositionSide,
                        fLeverage,
                        fLiqPrice,
                        fMarkPrice,
                        marginBalance,
                        fRealizedPnl,
                        "liquidation triggered at markPrice=" + fMarkPrice,
                        Instant.now()));
            }
        });
    }
}
