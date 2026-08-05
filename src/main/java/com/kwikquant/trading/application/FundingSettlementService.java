package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.FundingSettlementEvent;
import com.kwikquant.trading.domain.FundingSettlement;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.FundingSettlementMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 资金费率结算服务(档位 B)。处理 OKX bills type=8 资金费率账单,落账 funding_settlements 表 +
 * audit + afterCommit publish {@link FundingSettlementEvent}。
 *
 * <p>五步事务(仿 {@link LiquidationService}):
 * <ol>
 *   <li>找本地 position(可空,平仓后资金费率仍结算)→ 拿 positionId + qtyAtSettle</li>
 *   <li>INSERT funding_settlements(UNIQUE(account_id, bill_id) 幂等;DuplicateKeyException 当已处理 return)</li>
 *   <li>audit_logs action=FUNDING_SETTLE targetType=POSITION</li>
 *   <li>afterCommit publishEvent(FundingSettlementEvent)——事务提交后才发</li>
 * </ol>
 *
 * <p><b>不调 BalanceService</b>:实盘资金费率由交易所侧扣减(同 {@code applyLiquidationDelta} 实盘 noop)。
 * PAPER 资金费率 8h @Scheduled 模拟(档位 C-2):PaperFundingSettlementScheduler 算 fundingAmount 后调
 * PaperBalanceAdapter.applyFundingSettlement(扣余额)+ processFundingSettlement(本方法落账 + event)。
 *
 * <p><b>fundingRate 留空</b>:OKX bills type=8 不返费率(只返 amt 金额),fundingRate 填 null,
 * 未来需展示费率时拉 /api/v5/public/funding-rate。
 */
@Service
public class FundingSettlementService {

    private static final Logger log = LoggerFactory.getLogger(FundingSettlementService.class);

    private final PositionService positionService;
    private final ExchangeAccountService accountService;
    private final FundingSettlementMapper fundingSettlementMapper;
    private final AuditRepository auditRepository;
    private final ApplicationEventPublisher eventPublisher;

    public FundingSettlementService(
            PositionService positionService,
            ExchangeAccountService accountService,
            FundingSettlementMapper fundingSettlementMapper,
            AuditRepository auditRepository,
            ApplicationEventPublisher eventPublisher) {
        this.positionService = positionService;
        this.accountService = accountService;
        this.fundingSettlementMapper = fundingSettlementMapper;
        this.auditRepository = auditRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 处理 OKX 资金费率账单(type=8)。五步事务落账 + 事件。
     *
     * @param bill OKX 账单(consumer 应提前过滤 type=8 才调本方法)
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void processFundingBill(CcxtOrderAdapter.BillRecord bill) {
        long accountId = bill.accountId();
        String symbol = bill.symbol();
        String posSideRaw = bill.posSide();
        PositionSide side =
                "long".equals(posSideRaw) ? PositionSide.LONG : "short".equals(posSideRaw) ? PositionSide.SHORT : null;

        // 步骤 1:找本地 position(可空,平仓后资金费率仍结算)
        Position position = positionService.findPerpPositionBySide(accountId, symbol, side);
        Long positionId = position != null ? position.getId() : null;
        BigDecimal qtyAtSettle = position != null && position.getQty() != null ? position.getQty() : BigDecimal.ZERO;
        if (bill.posBal() != null) {
            qtyAtSettle = bill.posBal(); // OKX 返的结算后持仓量优先
        }

        ExchangeAccount acct = accountService.findById(accountId);
        long userId = acct != null ? acct.getUserId() : 0L;

        // 步骤 2:INSERT funding_settlements(UNIQUE(account_id, bill_id) 幂等)
        FundingSettlement s = new FundingSettlement();
        s.setAccountId(accountId);
        s.setPositionId(positionId);
        s.setSymbol(symbol);
        s.setFundingRate(null); // OKX bills 不返费率,留空
        s.setQtyAtSettle(qtyAtSettle);
        s.setFundingAmount(bill.amt() != null ? bill.amt() : BigDecimal.ZERO);
        s.setSettleTime(bill.ts() != null ? bill.ts() : Instant.now());
        s.setBillId(bill.billId());
        try {
            fundingSettlementMapper.insert(s);
        } catch (DuplicateKeyException e) {
            // 幂等:同 billId 已处理(UNIQUE(account_id, bill_id) 撞键),跳过不重复落账
            log.info("[funding] duplicate bill skipped (idempotent): accountId={} billId={}", accountId, bill.billId());
            return;
        }

        // 步骤 3:audit_logs(action=FUNDING_SETTLE targetType=POSITION targetId=positionId)
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("billId", bill.billId());
        metadata.put("symbol", symbol);
        metadata.put("posSide", posSideRaw);
        if (positionId != null) {
            metadata.put("positionId", positionId);
        }
        metadata.put("fundingAmount", s.getFundingAmount());
        metadata.put("qtyAtSettle", qtyAtSettle);
        auditRepository.save(new AuditEntry(
                "system",
                "FUNDING_SETTLE",
                "POSITION",
                positionId != null ? String.valueOf(positionId) : null,
                null,
                AuditEntry.STATUS_SUCCESS,
                null,
                metadata,
                Instant.now()));

        // 步骤 4:afterCommit publishEvent(FundingSettlementEvent)——事务提交后才发
        final long fUserId = userId;
        final long fAccountId = accountId;
        final Long fPositionId = positionId;
        final String fSymbol = symbol;
        final BigDecimal fAmount = s.getFundingAmount();
        final BigDecimal fQty = qtyAtSettle;
        final Instant fSettleTime = s.getSettleTime();
        final String fBillId = bill.billId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(new FundingSettlementEvent(
                        fUserId,
                        fAccountId,
                        fPositionId,
                        fSymbol,
                        null, // fundingRate null(OKX bills 不返)
                        fQty,
                        fAmount,
                        fSettleTime,
                        fBillId,
                        Instant.now()));
            }
        });
    }

    /**
     * PAPER 资金费率 8h 结算落账(档位 C-2)。不走 bills(PAPER 无 OKX bills),
     * 由 {@code PaperFundingSettlementScheduler} 算 fundingAmount 后调本方法落账 + event。
     * 余额扣减由 PaperFundingSettlementScheduler 调 {@code PaperBalanceAdapter.applyFundingSettlement}。
     *
     * <p>幂等键 billId = "PAPER-{positionId}-{settleTime}"(同一仓同一结算时刻不重复落账,
     * 复用 UNIQUE(account_id, bill_id))。PAPER/LIVE 按 account_id 区分(PAPER account 无 LIVE bill)。
     *
     * @param accountId 账户 ID
     * @param positionId 持仓 ID(可空,平仓后资金费率仍结算)
     * @param symbol canonical BTC/USDT
     * @param fundingRate 资金费率(从 fetchFundingRate 拉,PAPER 有值,区别于实盘 bills null)
     * @param qty 结算时持仓量
     * @param fundingAmount 资金费金额(已带符号:正=收加 free,负=付扣 free)
     * @param settleTime 结算时刻(8h 整点)
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void processFundingSettlement(
            long accountId,
            Long positionId,
            String symbol,
            BigDecimal fundingRate,
            BigDecimal qty,
            BigDecimal fundingAmount,
            Instant settleTime) {
        ExchangeAccount acct = accountService.findById(accountId);
        long userId = acct != null ? acct.getUserId() : 0L;

        String paperBillId = "PAPER-" + (positionId != null ? positionId : "npos") + "-" + settleTime.toEpochMilli();

        FundingSettlement s = new FundingSettlement();
        s.setAccountId(accountId);
        s.setPositionId(positionId);
        s.setSymbol(symbol);
        s.setFundingRate(fundingRate);
        s.setQtyAtSettle(qty != null ? qty : BigDecimal.ZERO);
        s.setFundingAmount(fundingAmount != null ? fundingAmount : BigDecimal.ZERO);
        s.setSettleTime(settleTime);
        s.setBillId(paperBillId);
        try {
            fundingSettlementMapper.insert(s);
        } catch (DuplicateKeyException e) {
            log.info(
                    "[funding] PAPER duplicate settle skipped: accountId={} positionId={} settleTime={}",
                    accountId,
                    positionId,
                    settleTime);
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "PAPER");
        metadata.put("symbol", symbol);
        if (positionId != null) metadata.put("positionId", positionId);
        metadata.put("fundingRate", fundingRate);
        metadata.put("fundingAmount", s.getFundingAmount());
        metadata.put("qtyAtSettle", s.getQtyAtSettle());
        auditRepository.save(new AuditEntry(
                "system",
                "FUNDING_SETTLE",
                "POSITION",
                positionId != null ? String.valueOf(positionId) : null,
                null,
                AuditEntry.STATUS_SUCCESS,
                null,
                metadata,
                Instant.now()));

        final long fUserId = userId;
        final long fAccountId = accountId;
        final Long fPositionId = positionId;
        final String fSymbol = symbol;
        final BigDecimal fRate = fundingRate;
        final BigDecimal fQty = s.getQtyAtSettle();
        final BigDecimal fAmount = s.getFundingAmount();
        final Instant fSettleTime = settleTime;
        final String fBillId = paperBillId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(new FundingSettlementEvent(
                        fUserId,
                        fAccountId,
                        fPositionId,
                        fSymbol,
                        fRate,
                        fQty,
                        fAmount,
                        fSettleTime,
                        fBillId,
                        Instant.now()));
            }
        });
    }
}
