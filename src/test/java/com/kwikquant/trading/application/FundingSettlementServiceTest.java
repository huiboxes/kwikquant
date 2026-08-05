package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.AuditEntry;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.shared.types.FundingSettlementEvent;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
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
 * FundingSettlementService 单测(档位 B)。聚焦:
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
    private FundingSettlementService service;

    @BeforeEach
    void setUp() {
        positionService = mock(PositionService.class);
        accountService = mock(ExchangeAccountService.class);
        fundingSettlementMapper = mock(FundingSettlementMapper.class);
        auditRepository = mock(AuditRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new FundingSettlementService(
                positionService, accountService, fundingSettlementMapper, auditRepository, eventPublisher);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static CcxtOrderAdapter.BillRecord bill(String billId, String posSide, BigDecimal amt) {
        return new CcxtOrderAdapter.BillRecord(
                7L, billId, 8, "0", "BTC/USDT", posSide, "USDT", amt, new BigDecimal("0.0025"), null, Instant.now());
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
        when(accountService.findById(7L)).thenReturn(acct);

        service.processFundingBill(bill("bill-1", "long", new BigDecimal("-0.0125")));

        verify(fundingSettlementMapper).insert(any());
        verify(auditRepository).save(any(AuditEntry.class));
        // 手动 trigger afterCommit → publishEvent
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
            if (s instanceof TransactionSynchronization ts) {
                ts.afterCommit();
            }
        });
        verify(eventPublisher).publishEvent(any(FundingSettlementEvent.class));
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
    }
}
