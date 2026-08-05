package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.BalanceService;
import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.shared.infra.AuditRepository;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.FillMapper;
import com.kwikquant.trading.infrastructure.OrderMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * LiquidationService 单测(档位 B)。聚焦 processLiquidationReport(实盘强平 type=5 / ADL type=9 分支):
 * <ul>
 *   <li>position null(bills 漏拉或已 flat)→ log warn skip,不调 processLiquidation</li>
 *   <li>markPrice null(bill.markPx + position.liquidationPrice 都 null)→ log warn skip</li>
 * </ul>
 *
 * <p>processLiquidation(五步事务)已有 ExecutionServiceProcessLiquidationTest 集成测试覆盖,
 * 本 class 只测 processLiquidationReport 的"找 position + fallback markPrice"分支。
 */
class LiquidationServiceTest {

    private PositionService positionService;
    private ExchangeAccountService accountService;
    private BalanceService balanceService;
    private OrderMapper orderMapper;
    private FillMapper fillMapper;
    private AuditRepository auditRepository;
    private ApplicationEventPublisher eventPublisher;
    private LiquidationService service;

    @BeforeEach
    void setUp() {
        positionService = mock(PositionService.class);
        accountService = mock(ExchangeAccountService.class);
        balanceService = mock(BalanceService.class);
        orderMapper = mock(OrderMapper.class);
        fillMapper = mock(FillMapper.class);
        auditRepository = mock(AuditRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new LiquidationService(
                positionService,
                accountService,
                balanceService,
                orderMapper,
                fillMapper,
                auditRepository,
                eventPublisher);
    }

    private static CcxtOrderAdapter.BillRecord bill(int type, String posSide, BigDecimal markPx) {
        return new CcxtOrderAdapter.BillRecord(
                7L,
                "bill-x",
                type,
                "0",
                "BTC/USDT",
                posSide,
                "USDT",
                new BigDecimal("-0.01"),
                new BigDecimal("0"),
                markPx,
                Instant.now());
    }

    @Test
    void processLiquidationReport_noLocalPosition_logsWarnSkips() {
        when(positionService.findPerpPositionBySide(7L, "BTC/USDT", PositionSide.LONG))
                .thenReturn(null);

        service.processLiquidationReport(bill(5, "long", new BigDecimal("42000")));

        verify(orderMapper, never()).insert(any());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void processLiquidationReport_noMarkPrice_logsWarnSkips() {
        Position pos = Position.flat(7L, "BTC/USDT");
        pos.setId(128L);
        pos.setLiquidationPrice(null); // 无强平价
        when(positionService.findPerpPositionBySide(7L, "BTC/USDT", PositionSide.LONG))
                .thenReturn(pos);

        service.processLiquidationReport(bill(9, "long", null)); // bill.markPx=null

        verify(orderMapper, never()).insert(any());
        verify(auditRepository, never()).save(any());
    }
}
