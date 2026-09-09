package com.kwikquant.trading.application;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRatePeriod;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.FundingSettlementEvent;
import com.kwikquant.trading.domain.BillRecord;
import com.kwikquant.trading.domain.FundingRateKind;
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
 * 资金费率结算服务(V59 期次化)。处理 OKX bills type=8 资金费率账单与 PAPER 期次结算,
 * 落账 funding_settlements 表 + audit + afterCommit publish {@link FundingSettlementEvent}。
 *
 * <p>事务步骤(仿 {@link LiquidationService}):
 * <ol>
 *   <li>找本地 position(可空,平仓后资金费率仍结算)→ 拿 positionId + qtyAtSettle</li>
 *   <li>INSERT funding_settlements(双幂等键 UNIQUE(account_id, bill_id) 与期次键
 *       UNIQUE(account_id, position_id, funding_time);DuplicateKeyException 当已处理 return)</li>
 *   <li>audit_logs action=FUNDING_SETTLE targetType=POSITION</li>
 *   <li>afterCommit publishEvent(FundingSettlementEvent)——事务提交后才发</li>
 * </ol>
 *
 * <p><b>实盘 processFundingBill 不扣余额</b>:实盘资金费率由交易所侧扣减(同 {@code applyLiquidationDelta} 实盘 noop)。
 * <b>PAPER processFundingSettlement 事务内扣余额</b>:insert funding_settlements 成功后同事务调
 * {@link BalanceService#applyFundingSettlement}(扣/加 paper_balance),DuplicateKey 早返不扣,
 * 扣减异常则整事务回滚(insert 也回滚),重跑撞期次幂等键不重复扣——原子且幂等。
 *
 * <p><b>LIVE 富化 best-effort</b>:OKX bills type=8 只返 amt 金额不返费率;bill.ts 即期次键,
 * 从本地 funding_rates 期序列反查 settledRate/intervalSeconds/markPrice(采集未覆盖则保持
 * null,不阻断账单落账——钱在交易所侧已发生,本地是记账与审计)。
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
    private final MarketDataService marketDataService;

    public FundingSettlementService(
            PositionService positionService,
            ExchangeAccountService accountService,
            FundingSettlementMapper fundingSettlementMapper,
            AuditRepository auditRepository,
            ApplicationEventPublisher eventPublisher,
            BalanceService balanceService,
            MarketDataService marketDataService) {
        this.positionService = positionService;
        this.accountService = accountService;
        this.fundingSettlementMapper = fundingSettlementMapper;
        this.auditRepository = auditRepository;
        this.eventPublisher = eventPublisher;
        this.balanceService = balanceService;
        this.marketDataService = marketDataService;
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

        // 步骤 1:找本地 position(可空,平仓后资金费率仍结算)。
        // qty_at_settle 一律取本地 position.qty(币语义):bill.posBal 单位无法机读核实(张/币/USDT
        // 均有可能),充当数量来源会污染币口径账目,只留审计。
        Position position = positionService.findPerpPositionBySide(accountId, symbol, side);
        Long positionId = position != null ? position.getId() : null;
        BigDecimal qtyAtSettle = position != null && position.getQty() != null ? position.getQty() : BigDecimal.ZERO;

        ExchangeAccount acct = accountService.findById(accountId);
        long userId = acct != null ? acct.getUserId() : 0L;

        // 步骤 2:INSERT funding_settlements(UNIQUE(account_id, bill_id) + 期次键双幂等)。
        // ts null = OKX 返回体异常:now() 兜底会污染期次键(watermark/幂等/对账全按它),
        // 与 null billId 同纪律——warn 跳过不落账(账单仍可在 OKX 侧追溯)
        if (bill.ts() == null) {
            log.warn(
                    "[funding] bill without ts skipped (period key would be polluted): accountId={} billId={}",
                    accountId,
                    bill.billId());
            return;
        }
        Instant fundingTime = bill.ts();
        FundingSettlement s = new FundingSettlement();
        s.setAccountId(accountId);
        s.setPositionId(positionId);
        s.setSymbol(symbol);
        s.setQtyAtSettle(qtyAtSettle);
        s.setFundingAmount(bill.amt() != null ? bill.amt() : BigDecimal.ZERO);
        s.setSettleTime(fundingTime);
        s.setFundingTime(fundingTime);
        s.setRateKind(FundingRateKind.SETTLED); // LIVE 账单 = 交易所侧已结算事实
        s.setBillId(bill.billId());
        // best-effort 富化:bill.ts 即期次键,从本地 funding_rates 反查费率/间隔/标记价。
        // 采集未覆盖保持 null(不阻断落账);fundingRate 不再恒 null,可对账。
        if (acct != null && acct.getExchange() != null && bill.ts() != null) {
            FundingRatePeriod period = marketDataService
                    .findFundingPeriod(acct.getExchange(), symbol, fundingTime)
                    .orElse(null);
            if (period != null) {
                s.setFundingRate(period.settledRate());
                s.setIntervalSeconds(period.intervalSeconds());
                s.setMarkPrice(period.markPrice());
            }
        }
        try {
            fundingSettlementMapper.insert(s);
        } catch (DuplicateKeyException e) {
            // 幂等:同 billId 或同期次已处理(UNIQUE(account_id, bill_id) / 期次键撞键),跳过不重复落账
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
        final BigDecimal fRate = s.getFundingRate(); // 富化命中则有值,未命中 null
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
     * PAPER 资金费期次结算落账。不走 bills(PAPER 无 OKX bills),由
     * {@code PaperFundingSettlementScheduler} 按期次网格算好金额后调本方法。
     *
     * <p>事务内:① INSERT funding_settlements(期次键 UNIQUE(account_id, position_id,
     * funding_time) 幂等,DuplicateKey 早返不扣)→ ②
     * {@link BalanceService#applyFundingSettlement} 扣/加 paper_balance(同事务,扣减异常回滚
     * insert)→ ③ audit → ④ afterCommit publishEvent(billId 传 null,不暴露 "PAPER-" 前缀)。
     *
     * <p>幂等键 billId = "PAPER-{positionId}-{fundingTimeEpochSecond}":绑<b>期次网格</b>而非
     * 跑批墙钟——同期内重跑/多实例/手动触发都撞键不双扣,宕机后补结也不漏。仅存 DB 做幂等,
     * event 不携带(防泄露 PAPER/LIVE 枚举)。settle_time := funding_time(期次语义)。
     *
     * <p>cmd.fundingAmount 已带符号(正=收加余额,负=付扣余额;OKX 正费率多头付→LONG 传负),
     * 币种用 cmd.currency()(symbol quote 段派生,不再硬编码 USDT)。
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void processFundingSettlement(FundingSettleCommand cmd) {
        if (cmd.fundingTime() == null) {
            throw new IllegalArgumentException("fundingTime must not be null (period key)");
        }
        if (cmd.rateKind() == null) {
            throw new IllegalArgumentException("rateKind must not be null");
        }
        if (cmd.currency() == null || cmd.currency().isBlank()) {
            throw new IllegalArgumentException("currency must not be blank (settlement balance currency)");
        }
        ExchangeAccount acct = accountService.findById(cmd.accountId());
        long userId = acct != null ? acct.getUserId() : 0L;

        // npos 兜底键带 symbol:同 epoch 不同标的的无仓位结算不撞 (account_id, bill_id) 幂等键
        String paperBillId = "PAPER-" + (cmd.positionId() != null ? cmd.positionId() : "npos-" + cmd.symbol()) + "-"
                + cmd.fundingTime().getEpochSecond();

        FundingSettlement s = new FundingSettlement();
        s.setAccountId(cmd.accountId());
        s.setPositionId(cmd.positionId());
        s.setSymbol(cmd.symbol());
        s.setFundingRate(cmd.fundingRate());
        s.setQtyAtSettle(cmd.qty() != null ? cmd.qty() : BigDecimal.ZERO);
        s.setFundingAmount(cmd.fundingAmount() != null ? cmd.fundingAmount() : BigDecimal.ZERO);
        s.setSettleTime(cmd.fundingTime());
        s.setFundingTime(cmd.fundingTime());
        s.setRateKind(cmd.rateKind());
        s.setIntervalSeconds(cmd.intervalSeconds());
        s.setMarkPrice(cmd.markPrice());
        s.setBillId(paperBillId);
        try {
            fundingSettlementMapper.insert(s);
        } catch (DuplicateKeyException e) {
            log.info(
                    "[funding] PAPER duplicate settle skipped (idempotent): accountId={} positionId={}"
                            + " fundingTime={}",
                    cmd.accountId(),
                    cmd.positionId(),
                    cmd.fundingTime());
            return;
        }

        // 余额落账(同事务,异常回滚 insert,DuplicateKey 早返不扣 → 原子幂等)。按 marginMode 分流:
        // ISOLATED 资金费侵蚀/增厚仓位保证金(OKX 逐仓语义)——仓位侧 frozenAmount += amount 与
        // liquidationPrice 重算走 applyFundingErosion(两本账同事务一致),余额侧动 used/total 不动 free;
        // 仓位已 flat(平仓先于结算落地,保证金已释放回账户)或 CROSS → 账户现金口径(free/total)。
        boolean eroded = cmd.marginMode() == com.kwikquant.shared.types.MarginMode.ISOLATED
                && cmd.positionId() != null
                && positionService.applyFundingErosion(cmd.positionId(), s.getFundingAmount(), cmd.fundingTime());
        if (eroded) {
            balanceService.applyIsolatedFundingErosion(cmd.accountId(), true, cmd.currency(), s.getFundingAmount());
        } else {
            balanceService.applyFundingSettlement(cmd.accountId(), true, cmd.currency(), s.getFundingAmount());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "PAPER");
        metadata.put("symbol", cmd.symbol());
        if (cmd.positionId() != null) metadata.put("positionId", cmd.positionId());
        metadata.put("fundingRate", cmd.fundingRate());
        metadata.put("rateKind", cmd.rateKind().name());
        // 费率行来源(EXCHANGE/PROXY_BINANCE):跨所代理值入账必须留审计痕迹;null(未知)不写
        if (cmd.rateSource() != null) metadata.put("rateSource", cmd.rateSource());
        metadata.put("fundingTime", cmd.fundingTime());
        metadata.put("fundingAmount", s.getFundingAmount());
        metadata.put("qtyAtSettle", s.getQtyAtSettle());
        auditRepository.save(new AuditEntry(
                "system",
                "FUNDING_SETTLE",
                "POSITION",
                cmd.positionId() != null ? String.valueOf(cmd.positionId()) : null,
                null,
                AuditEntry.STATUS_SUCCESS,
                null,
                metadata,
                Instant.now()));

        final long fUserId = userId;
        final long fAccountId = cmd.accountId();
        final Long fPositionId = cmd.positionId();
        final String fSymbol = cmd.symbol();
        final BigDecimal fRate = cmd.fundingRate();
        final BigDecimal fQty = s.getQtyAtSettle();
        final BigDecimal fAmount = s.getFundingAmount();
        final Instant fSettleTime = s.getSettleTime();
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

    /**
     * 某持仓最近已结算期次的 watermark(MAX(funding_time)),无记录返 null。
     * PAPER catch-up 结算下界(与 positions.opened_at 取 max,见
     * {@link PaperFundingSettlementScheduler})。
     */
    public Instant findLastFundingTime(long accountId, long positionId) {
        return fundingSettlementMapper.findLastFundingTime(accountId, positionId);
    }
}
