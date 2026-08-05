package com.kwikquant.trading.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter.AccountSnapshot;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter.PositionSnapshot;
import com.kwikquant.trading.infrastructure.PositionMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PositionReconcileScheduler 单测(bills 5s 轮询的 60s 兜底)。
 *
 * <p>聚焦兜底强平分支:本地 open PERP + OKX 无 → 调 LiquidationService.processLiquidation;
 * 无 open 持仓 / OKX 有 / 模拟盘账户 → 跳过。
 */
class PositionReconcileSchedulerTest {

    private ExchangeAccountService accountService;
    private CcxtOrderAdapter ccxtAdapter;
    private PositionMapper positionMapper;
    private LiquidationService liquidationService;
    private PositionReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        ccxtAdapter = mock(CcxtOrderAdapter.class);
        positionMapper = mock(PositionMapper.class);
        liquidationService = mock(LiquidationService.class);
        scheduler = new PositionReconcileScheduler(accountService, ccxtAdapter, positionMapper, liquidationService);
    }

    private static ExchangeAccount realOkxAccount(long id) {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(id);
        a.setExchange(Exchange.OKX);
        a.setPaperTrading(false);
        return a;
    }

    private static Position openPerp(long id, String symbol, String posSide, BigDecimal qty, BigDecimal liqPrice) {
        Position p = Position.flat(7L, symbol);
        p.setId(id);
        p.setMarginMode(MarginMode.ISOLATED);
        p.setPositionSide(posSide);
        p.setQty(qty);
        p.setLiquidationPrice(liqPrice);
        return p;
    }

    private static PositionSnapshot okxSnapshot(String posSide, BigDecimal qty) {
        return new PositionSnapshot(
                "BTC/USDT",
                posSide,
                qty,
                new BigDecimal("42150"),
                MarketType.PERP,
                PositionSide.LONG,
                10,
                MarginMode.ISOLATED,
                new BigDecimal("37105"),
                new BigDecimal("42000"),
                new BigDecimal("2.05"),
                new BigDecimal("0.5"));
    }

    @Test
    void reconcile_noOpenPerp_skipsFetchSnapshot() {
        when(accountService.findAll()).thenReturn(List.of(realOkxAccount(7L)));
        when(positionMapper.findByAccount(7L)).thenReturn(List.of());

        scheduler.reconcile();

        verify(ccxtAdapter, never()).fetchSnapshot(any());
        verify(liquidationService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void reconcile_localOpenButOkxEmpty_callsProcessLiquidation() {
        ExchangeAccount acct = realOkxAccount(7L);
        when(accountService.findAll()).thenReturn(List.of(acct));
        Position local = openPerp(128L, "BTC/USDT", "LONG", new BigDecimal("0.0025"), new BigDecimal("37105"));
        when(positionMapper.findByAccount(7L)).thenReturn(List.of(local));
        when(ccxtAdapter.fetchSnapshot(acct)).thenReturn(new AccountSnapshot(List.of(), List.of()));

        scheduler.reconcile();

        verify(liquidationService).processLiquidation(eq(128L), eq(new BigDecimal("37105")), eq(null));
    }

    @Test
    void reconcile_localOpenAndOkxHasIt_skipsProcessLiquidation() {
        ExchangeAccount acct = realOkxAccount(7L);
        when(accountService.findAll()).thenReturn(List.of(acct));
        Position local = openPerp(128L, "BTC/USDT", "LONG", new BigDecimal("0.0025"), new BigDecimal("37105"));
        when(positionMapper.findByAccount(7L)).thenReturn(List.of(local));
        when(ccxtAdapter.fetchSnapshot(acct))
                .thenReturn(new AccountSnapshot(List.of(), List.of(okxSnapshot("long", new BigDecimal("0.0025")))));

        scheduler.reconcile();

        verify(liquidationService, never()).processLiquidation(anyLong(), any(), any());
    }

    @Test
    void reconcile_paperAccountSkipped() {
        ExchangeAccount paper = new ExchangeAccount();
        paper.setId(8L);
        paper.setExchange(Exchange.OKX);
        paper.setPaperTrading(true);
        when(accountService.findAll()).thenReturn(List.of(paper));

        scheduler.reconcile();

        verify(positionMapper, never()).findByAccount(anyLong());
        verify(ccxtAdapter, never()).fetchSnapshot(any());
    }
}
