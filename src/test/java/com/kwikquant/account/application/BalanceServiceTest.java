package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.OrderSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BalanceService 单元测试。验证:
 * 1. fetchBalance PAPER 委托 paperBalanceAdapter.fetch(不再写死 BalanceSnapshot.paper())
 * 2. fetchBalance 真实交易所走 CCXT(保留原逻辑,LiveBalanceAdapter 迁移在 Batch 5)
 * 3. freeze/applyFill/reset 委托 paperBalanceAdapter;freeze/applyFill 非 PAPER 静默 noop
 *    (真实交易所余额由交易所维护,本地不记账);reset 非 PAPER 抛 IllegalArgumentException
 *    (重置只对 PAPER 账户)。
 */
class BalanceServiceTest {

    private ExchangeAccountService accountService;
    private KeyManagementService keyManagementService;
    private PaperBalanceAdapter paperBalanceAdapter;
    private BalanceService balanceService;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        keyManagementService = mock(KeyManagementService.class);
        paperBalanceAdapter = mock(PaperBalanceAdapter.class);
        balanceService = new BalanceService(accountService, keyManagementService, paperBalanceAdapter);
    }

    // --- fetchBalance ---
    @Test
    void fetchBalance_paper_delegatesToPaperAdapter() {
        ExchangeAccount paper = new ExchangeAccount();
        paper.setId(1L);
        paper.setUserId(42L);
        paper.setExchange(Exchange.PAPER);
        when(accountService.getOwned(1L, 42L)).thenReturn(paper);
        BalanceSnapshot stub = BalanceSnapshot.paper();
        when(paperBalanceAdapter.fetch(paper)).thenReturn(stub);

        BalanceSnapshot snapshot = balanceService.fetchBalance(1L, 42L);

        assertThat(snapshot).isSameAs(stub);
        verify(keyManagementService, never()).decryptSecret(any(ExchangeAccount.class));
    }

    @Test
    void fetchBalance_real_throwsOnApiError() {
        ExchangeAccount binance = new ExchangeAccount();
        binance.setId(2L);
        binance.setUserId(42L);
        binance.setExchange(Exchange.BINANCE);
        binance.setApiKey("test-key");
        binance.setApiSecret(new byte[] {1, 2, 3});
        binance.setNonce(new byte[12]);
        binance.setKeyVersion(1);
        when(accountService.getOwned(2L, 42L)).thenReturn(binance);
        when(keyManagementService.decryptSecret(binance)).thenReturn("test-secret".getBytes());
        when(keyManagementService.decryptPassphrase(binance)).thenReturn(null);

        assertThatThrownBy(() -> balanceService.fetchBalance(2L, 42L))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("fetchBalance failed");
        verify(paperBalanceAdapter, never()).fetch(any(ExchangeAccount.class));
    }

    @Test
    void paper_snapshotHasExpectedStructure() {
        BalanceSnapshot snapshot = BalanceSnapshot.paper();
        assertThat(snapshot.currencies()).hasSize(1);
        var usdt = snapshot.currencies().get("USDT");
        assertThat(usdt.free()).isEqualByComparingTo("100000");
        assertThat(usdt.used()).isEqualByComparingTo("0");
        assertThat(usdt.total()).isEqualByComparingTo("100000");
    }

    // --- freeze ---
    @Test
    void freeze_paper_delegatesToPaperAdapter() {
        balanceService.freeze(1L, Exchange.PAPER, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter).freeze(1L, "USDT", new BigDecimal("1000"));
    }

    @Test
    void freeze_real_isNoop() {
        balanceService.freeze(1L, Exchange.BINANCE, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter, never()).freeze(anyLong(), anyString(), any(BigDecimal.class));
    }

    // --- unfreeze ---
    @Test
    void unfreeze_paper_delegatesToPaperAdapter() {
        balanceService.unfreeze(1L, Exchange.PAPER, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter).unfreeze(1L, "USDT", new BigDecimal("1000"));
    }

    @Test
    void unfreeze_real_isNoop() {
        balanceService.unfreeze(1L, Exchange.BINANCE, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter, never()).unfreeze(anyLong(), anyString(), any(BigDecimal.class));
    }

    // --- applyFill ---
    @Test
    void applyFill_paper_delegatesToPaperAdapter() {
        balanceService.applyFill(
                1L, Exchange.PAPER, OrderSide.BUY, "BTC/USDT",
                new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("5"));

        verify(paperBalanceAdapter)
                .applyFill(
                        1L,
                        OrderSide.BUY,
                        "BTC/USDT",
                        new BigDecimal("0.1"),
                        new BigDecimal("50000"),
                        new BigDecimal("5"));
    }

    @Test
    void applyFill_real_isNoop() {
        balanceService.applyFill(
                1L, Exchange.BINANCE, OrderSide.BUY, "BTC/USDT",
                new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("5"));

        verify(paperBalanceAdapter, never())
                .applyFill(
                        anyLong(),
                        any(OrderSide.class),
                        anyString(),
                        any(BigDecimal.class),
                        any(BigDecimal.class),
                        any(BigDecimal.class));
    }

    // --- reset ---
    @Test
    void reset_paper_delegatesToPaperAdapter() {
        balanceService.reset(1L, Exchange.PAPER);

        verify(paperBalanceAdapter).reset(1L);
    }

    @Test
    void reset_real_throwsIllegalArgument() {
        assertThatThrownBy(() -> balanceService.reset(1L, Exchange.BINANCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAPER");
        verify(paperBalanceAdapter, never()).reset(anyLong());
    }
}
