package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRatePeriod;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.FundingSettlementEvent;
import com.kwikquant.trading.domain.BillRecord;
import com.kwikquant.trading.domain.BillType;
import com.kwikquant.trading.domain.FundingRateKind;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.FundingSettlementMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * FundingSettlementService 单测。聚焦:
 * <ul>
 *   <li>happy path:insert + audit + afterCommit publishEvent</li>
 *   <li>DuplicateKeyException 幂等跳过(UNIQUE(account_id, bill_id))</li>
 *   <li>无 position(positionId=null)仍落账(平仓后资金费率结算)</li>
 * </ul>
 *
 * <p>afterCommit publishEvent 用 {@link TransactionSynchronizationManager#initSynchronization()}
 * 手动激活事务同步(纯 Mockito 无 Spring 容器),手动 trigger afterCommit 验证事件发出。
 */
class FundingSettlementServiceTest {

    private PositionService positionService;
    private ExchangeAccountService accountService;
    private FundingSettlementMapper fundingSettlementMapper;
    private AuditRepository auditRepository;
    private ApplicationEventPublisher eventPublisher;
    private BalanceService balanceService;
    private MarketDataService marketDataService;
    private FundingSettlementService service;

    @BeforeEach
    void setUp() {
        positionService = mock(PositionService.class);
        accountService = mock(ExchangeAccountService.class);
        fundingSettlementMapper = mock(FundingSettlementMapper.class);
        auditRepository = mock(AuditRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        balanceService = mock(BalanceService.class);
        marketDataService = mock(MarketDataService.class);
        service = new FundingSettlementService(
                positionService,
                accountService,
                fundingSettlementMapper,
                auditRepository,
                eventPublisher,
                balanceService,
                marketDataService);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static BillRecord bill(String billId, String posSide, BigDecimal amt) {
        PositionSide side =
                "long".equals(posSide) ? PositionSide.LONG : "short".equals(posSide) ? PositionSide.SHORT : null;
        return new BillRecord(
                7L, billId, BillType.FUNDING, "BTC/USDT", side, amt, new BigDecimal("0.0025"), null, Instant.now());
    }

    @Test
    void processFundingBill_happyPath_insertsAndPublishesEvent() {
        Position pos = Position.flat(7L, "BTC/USDT");
        pos.setId(128L);
        pos.setQty(new BigDecimal("0.0025"));
        when(positionService.findPerpPositionBySide(7L, "BTC/USDT", PositionSide.LONG))
                .thenReturn(pos);
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        acct.setExchange(Exchange.OKX);
        when(accountService.findById(7L)).thenReturn(acct);

        service.processFundingBill(bill("bill-1", "long", new BigDecimal("-0.0125")));

        // 期次化落账:fundingTime=bill.ts、rateKind=SETTLED;本地 funding_rates 无该期(mock 返 empty)
        // → 富化字段保持 null(best-effort 不阻断)
        verify(fundingSettlementMapper)
                .insert(argThat(s -> s.getRateKind() == FundingRateKind.SETTLED
                        && s.getFundingTime() != null
                        && s.getFundingTime().equals(s.getSettleTime())
                        && s.getFundingRate() == null));
        verify(auditRepository).save(any(AuditEntry.class));
        // 手动 trigger afterCommit → publishEvent
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
            if (s instanceof TransactionSynchronization ts) {
                ts.afterCommit();
            }
        });
        // 实盘资金费率由交易所侧扣减,本地不扣余额
        verify(balanceService, never()).applyFundingSettlement(anyLong(), anyBoolean(), anyString(), any());
        verify(eventPublisher).publishEvent(any(FundingSettlementEvent.class));
    }

    @Test
    void processFundingBill_enrichesFromLocalFundingPeriod() {
        // 本地 funding_rates 有该期 settled 行 → bill 落账富化 rate/interval/markPrice(可对账)
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        acct.setExchange(Exchange.OKX);
        when(accountService.findById(7L)).thenReturn(acct);
        Position pos = Position.flat(7L, "BTC/USDT");
        pos.setId(128L);
        pos.setQty(new BigDecimal("0.0025"));
        when(positionService.findPerpPositionBySide(7L, "BTC/USDT", PositionSide.LONG))
                .thenReturn(pos);
        Instant period = Instant.parse("2026-08-05T00:00:00Z");
        when(marketDataService.findFundingPeriod(Exchange.OKX, "BTC/USDT", period))
                .thenReturn(java.util.Optional.of(new FundingRatePeriod(
                        period,
                        new BigDecimal("0.000123"),
                        new BigDecimal("0.0001"),
                        28800,
                        new BigDecimal("60000"),
                        "EXCHANGE")));
        BillRecord billRec = new BillRecord(
                7L,
                "bill-3",
                BillType.FUNDING,
                "BTC/USDT",
                PositionSide.LONG,
                new BigDecimal("-0.0125"),
                new BigDecimal("0.0025"),
                null,
                period);

        service.processFundingBill(billRec);

        verify(fundingSettlementMapper)
                .insert(argThat(s -> s.getFundingRate() != null
                        && s.getFundingRate().compareTo(new BigDecimal("0.000123")) == 0
                        && Integer.valueOf(28800).equals(s.getIntervalSeconds())
                        && s.getMarkPrice() != null
                        && s.getMarkPrice().compareTo(new BigDecimal("60000")) == 0
                        && s.getFundingTime().equals(period)));
    }

    @Test
    void processFundingBill_duplicateBillId_skipsIdempotent() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);
        doThrow(new DuplicateKeyException("dup")).when(fundingSettlementMapper).insert(any());

        service.processFundingBill(bill("bill-dup", "long", new BigDecimal("-0.01")));

        verify(fundingSettlementMapper).insert(any());
        verify(auditRepository, never()).save(any());
        verify(balanceService, never()).applyFundingSettlement(anyLong(), anyBoolean(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void processFundingBill_noPosition_stillInsertsWithNullPositionId() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);
        when(positionService.findPerpPositionBySide(anyLong(), anyString(), any()))
                .thenReturn(null);

        service.processFundingBill(bill("bill-2", "short", new BigDecimal("0.005")));

        verify(fundingSettlementMapper).insert(any());
        verify(auditRepository).save(any(AuditEntry.class));
        verify(balanceService, never()).applyFundingSettlement(anyLong(), anyBoolean(), anyString(), any());
    }

    private static FundingSettleCommand cmd(String currency, Instant fundingTime, FundingRateKind kind) {
        return cmd(currency, fundingTime, kind, com.kwikquant.shared.types.MarginMode.CROSS);
    }

    private static FundingSettleCommand cmd(
            String currency, Instant fundingTime, FundingRateKind kind, com.kwikquant.shared.types.MarginMode mode) {
        return new FundingSettleCommand(
                7L,
                128L,
                "BTC/USDT",
                new BigDecimal("0.0001"),
                new BigDecimal("0.0025"),
                new BigDecimal("-5"),
                fundingTime,
                kind,
                28800,
                new BigDecimal("60000"),
                currency,
                mode,
                "EXCHANGE");
    }

    @Test
    void processFundingSettlement_paper_insertsWithPeriodKeyAndBillIdPrefix() {
        // PAPER: fundingRate=0.0001(有值,区别于实盘 bills 富化失败时的 null), qty=0.0025, fundingAmount=-5(付)
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);
        Instant fundingTime = Instant.parse("2026-08-05T00:00:00Z");

        service.processFundingSettlement(cmd("USDT", fundingTime, FundingRateKind.SETTLED));

        // billId 绑期次网格(epoch second,不再绑跑批墙钟毫秒);settle_time := funding_time
        verify(fundingSettlementMapper)
                .insert(argThat(s -> s.getFundingRate() != null
                        && s.getFundingRate().compareTo(new BigDecimal("0.0001")) == 0
                        && ("PAPER-128-" + fundingTime.getEpochSecond()).equals(s.getBillId())
                        && fundingTime.equals(s.getFundingTime())
                        && fundingTime.equals(s.getSettleTime())
                        && s.getRateKind() == FundingRateKind.SETTLED
                        && Integer.valueOf(28800).equals(s.getIntervalSeconds())
                        && s.getMarkPrice() != null
                        && s.getMarkPrice().compareTo(new BigDecimal("60000")) == 0
                        && s.getFundingAmount() != null
                        && s.getFundingAmount().compareTo(new BigDecimal("-5")) == 0
                        && s.getQtyAtSettle().compareTo(new BigDecimal("0.0025")) == 0));
        verify(auditRepository).save(any(AuditEntry.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
            if (s instanceof TransactionSynchronization ts) {
                ts.afterCommit();
            }
        });
        // PAPER 事务内扣余额(insert 成功后调,amount=-5 付,currency 从 command 传入不再硬编码)
        verify(balanceService)
                .applyFundingSettlement(
                        eq(7L),
                        eq(true),
                        eq("USDT"),
                        argThat(bd -> bd != null && bd.compareTo(new BigDecimal("-5")) == 0));
        verify(eventPublisher)
                .publishEvent(argThat((Object e) -> e instanceof FundingSettlementEvent f && f.billId() == null));
    }

    @Test
    void processFundingSettlement_nonUsdtQuote_settlesInQuoteCurrency() {
        // F7 修复验证:结算币种跟随 symbol quote 段(如 DOGE/USDC → USDC),不再硬编码 USDT
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);

        service.processFundingSettlement(cmd("USDC", Instant.parse("2026-08-05T00:00:00Z"), FundingRateKind.SETTLED));

        verify(balanceService).applyFundingSettlement(eq(7L), eq(true), eq("USDC"), any());
    }

    @Test
    void processFundingSettlement_isolatedMarginMode_erodesPositionMargin() {
        // ISOLATED 逐仓语义:资金费直接收付仓位保证金——仓位侧 applyFundingErosion(frozen+=amount+liq 重算)
        // + 余额侧 used/total(不动 free),两本账同事务一致
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);
        when(positionService.applyFundingErosion(eq(128L), eq(new BigDecimal("-5")), any()))
                .thenReturn(true);

        service.processFundingSettlement(cmd(
                "USDT",
                Instant.parse("2026-08-05T00:00:00Z"),
                FundingRateKind.SETTLED,
                com.kwikquant.shared.types.MarginMode.ISOLATED));

        verify(positionService).applyFundingErosion(eq(128L), eq(new BigDecimal("-5")), any());
        verify(balanceService).applyIsolatedFundingErosion(7L, true, "USDT", new BigDecimal("-5"));
        verify(balanceService, never()).applyFundingSettlement(anyLong(), anyBoolean(), anyString(), any());
    }

    @Test
    void processFundingSettlement_isolatedButPositionGone_fallsBackToCash() {
        // 平仓先于结算落地(仓位保证金已释放回账户):applyFundingErosion 返 false →
        // 降级账户现金口径(free/total),该期资金费不漏收
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);
        when(positionService.applyFundingErosion(eq(128L), eq(new BigDecimal("-5")), any()))
                .thenReturn(false);

        service.processFundingSettlement(cmd(
                "USDT",
                Instant.parse("2026-08-05T00:00:00Z"),
                FundingRateKind.SETTLED,
                com.kwikquant.shared.types.MarginMode.ISOLATED));

        verify(balanceService).applyFundingSettlement(7L, true, "USDT", new BigDecimal("-5"));
        verify(balanceService, never()).applyIsolatedFundingErosion(anyLong(), anyBoolean(), anyString(), any());
    }

    @Test
    void processFundingSettlement_crossMarginMode_settlesToCashWithoutErosion() {
        // CROSS 全仓:账户担保即现金,不侵蚀仓位保证金(不动 positions.frozen_amount)
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);

        service.processFundingSettlement(cmd(
                "USDT",
                Instant.parse("2026-08-05T00:00:00Z"),
                FundingRateKind.SETTLED,
                com.kwikquant.shared.types.MarginMode.CROSS));

        verify(positionService, never()).applyFundingErosion(anyLong(), any(), any());
        verify(balanceService).applyFundingSettlement(7L, true, "USDT", new BigDecimal("-5"));
    }

    @Test
    void processFundingSettlement_paperDuplicate_skipsIdempotent() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setUserId(42L);
        when(accountService.findById(7L)).thenReturn(acct);
        doThrow(new DuplicateKeyException("dup")).when(fundingSettlementMapper).insert(any());

        service.processFundingSettlement(cmd("USDT", Instant.parse("2026-08-05T00:00:00Z"), FundingRateKind.SETTLED));

        verify(fundingSettlementMapper).insert(any());
        verify(balanceService, never()).applyFundingSettlement(anyLong(), anyBoolean(), anyString(), any());
        verify(auditRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void processFundingSettlement_missingPeriodKeyOrCurrency_failsFast() {
        // 期次键/费率类型/币种缺失 = 调用方 bug,fail-fast 拒绝(不落无期次键的账、不按 null 币种扣余额)
        assertThatThrownBy(() -> service.processFundingSettlement(cmd("USDT", null, FundingRateKind.SETTLED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fundingTime");
        assertThatThrownBy(() ->
                        service.processFundingSettlement(cmd("USDT", Instant.parse("2026-08-05T00:00:00Z"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateKind");
        assertThatThrownBy(() -> service.processFundingSettlement(
                        cmd(" ", Instant.parse("2026-08-05T00:00:00Z"), FundingRateKind.SETTLED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
        verify(fundingSettlementMapper, never()).insert(any());
    }
}
