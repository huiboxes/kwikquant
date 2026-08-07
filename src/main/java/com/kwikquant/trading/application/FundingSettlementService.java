package com.kwikquant.trading.application;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.FundingSettlementEvent;
import com.kwikquant.trading.domain.BillRecord;
import com.kwikquant.trading.domain.FundingSettlement;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.FundingSettlementMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 资金费率结算服务。处理 OKX bills type=8 资金费率账单,落账 funding_settlements 表 +
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
 * <p><b>实盘 processFundingBill 不扣余额</b>:实盘资金费率由交易所侧扣减(同 {@code applyLiquidationDelta} 实盘 noop)。
 * <b>PAPER processFundingSettlement 事务内扣余额</b>:insert funding_settlements 成功后同事务调
 * {@link BalanceService#applyFundingSettlement}(扣/加 paper_balance.free),DuplicateKey 早返不扣,
 * 扣减异常则整事务回滚(insert 也回滚),下个 8h 周期重跑不撞幂等键重新扣——原子且幂等。
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
    private final BalanceService balanceService;

    public FundingSettlementService(
            PositionService positionService,
            ExchangeAccountService accountService,
            FundingSettlementMapper fundingSettlementMapper,
            AuditRepository auditRepository,
            ApplicationEventPublisher eventPublisher,
            BalanceService balanceService) {
        this.positionService = positionService;
        this.accountService = accountService;
        this.fundingSettlementMapper = fundingSettlementMapper;
        this.auditRepository = auditRepository;
        this.eventPublisher = eventPublisher;
        this.balanceService = balanceService;
    }

    /**
     * 处理 OKX 资金费率账单(type=8)。五步事务落账 + 事件。
     *
     * @param bill OKX 账单(consumer 应提前过滤 type=8 才调本方法)
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void processFundingBill(BillRecord bill) {
        long accountId = bill.accountId();
        String symbol = bill.symbol();
        PositionSide side = bill.posSide(); // domain 已映射(OkxOrderTranslator "long"→LONG/"short"→SHORT/net→null)

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
        metadata.put("posSide", side != null ? side.name() : null);
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
     * PAPER 资金费率 8h 结算落账。不走 bills(PAPER 无 OKX bills),
     * 由 {@code PaperFundingSettlementScheduler} 算 fundingAmount 后调本方法。
     *
     * <p>事务内五步:① INSERT funding_settlements(UNIQUE 幂等,DuplicateKey 早返不扣)→
     * ② {@link BalanceService#applyFundingSettlement} 扣/加 paper_balance.free(同事务,扣减异常回滚 insert)→
     * ③ audit → ④ afterCommit publishEvent(billId 传 null,不暴露 "PAPER-" 前缀)。
     *
     * <p>幂等键 billId = "PAPER-{positionId}-{settleTime}"(同一仓同一结算时刻不重复落账,
     * 复用 UNIQUE(account_id, bill_id)),仅存 DB 做幂等,event 不携带(防泄露 PAPER/LIVE 枚举)。
     *
     * @param fundingAmount 资金费金额(已带符号:正=收加 free,负=付扣 free;OKX 正费率多头付→LONG 传负)
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

        // 扣/加 paper_balance.free(同事务,扣减异常回滚 insert,DuplicateKey 早返不扣 → 原子幂等)
        balanceService.applyFundingSettlement(accountId, true, "USDT", s.getFundingAmount());

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
        final String fBillId = null; // event 不携带 "PAPER-" 前缀(DB 保留 paperBillId 做幂等,防泄露 PAPER/LIVE 枚举)
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

    /**
     * 查资金费率结算历史明细(只读)。MCP {@code get_funding_history} + 前端明细查询用。
     * symbol 可空查全部,按 settle_time 倒序,limit 由调用方截断(建议 ≤200)。
     */
    public List<FundingSettlement> listByAccountAndSymbol(long accountId, String symbol, int limit) {
        return fundingSettlementMapper.listByAccountAndSymbol(accountId, symbol, limit);
    }
}
