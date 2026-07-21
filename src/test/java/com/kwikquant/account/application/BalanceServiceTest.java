package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.infra.ProxyProperties;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.OrderSide;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BalanceService 单元测试。验证:
 * 1. fetchBalance 按 isPaperTrading() 分流(不再看 exchange==PAPER):模拟盘委托
 *    paperBalanceAdapter.fetch,真实交易所走 CCXT。
 * 2. freeze/unfreeze/applyFill/reset 按 paperTrading 布尔参数分流;非模拟盘静默 noop
 *    (真实交易所余额由交易所维护,本地不记账);reset 非模拟盘抛 IllegalArgumentException。
 */
class BalanceServiceTest {

    private ExchangeAccountService accountService;
    private KeyManagementService keyManagementService;
    private PaperBalanceAdapter paperBalanceAdapter;
    private ProxyProperties proxyProperties;
    private QuoteCurrencyProperties quoteCurrencyProperties;
    private BalanceService balanceService;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        keyManagementService = mock(KeyManagementService.class);
        paperBalanceAdapter = mock(PaperBalanceAdapter.class);
        proxyProperties = new ProxyProperties(null, Map.of()); // 直连(单测不连真实交易所)
        quoteCurrencyProperties = new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));
        balanceService = new BalanceService(
                accountService, keyManagementService, paperBalanceAdapter, proxyProperties, quoteCurrencyProperties);
    }

    // --- fetchBalance ---
    @Test
    void fetchBalance_paper_delegatesToPaperAdapter() {
        ExchangeAccount paper = new ExchangeAccount();
        paper.setId(1L);
        paper.setUserId(42L);
        paper.setExchange(Exchange.BINANCE);
        paper.setPaperTrading(true);
        when(accountService.getOwned(1L, 42L)).thenReturn(paper);
        BalanceSnapshot stub = new BalanceSnapshot(Map.of(
                "USDT",
                new BalanceSnapshot.CurrencyBalance(
                        new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"))));
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
        binance.setPaperTrading(false);
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

    // --- freeze ---
    @Test
    void freeze_paper_delegatesToPaperAdapter() {
        balanceService.freeze(1L, true, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter).freeze(1L, "USDT", new BigDecimal("1000"));
    }

    @Test
    void freeze_real_isNoop() {
        balanceService.freeze(1L, false, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter, never()).freeze(anyLong(), anyString(), any(BigDecimal.class));
    }

    // --- unfreeze ---
    @Test
    void unfreeze_paper_delegatesToPaperAdapter() {
        balanceService.unfreeze(1L, true, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter).unfreeze(1L, "USDT", new BigDecimal("1000"));
    }

    @Test
    void unfreeze_real_isNoop() {
        balanceService.unfreeze(1L, false, "USDT", new BigDecimal("1000"));

        verify(paperBalanceAdapter, never()).unfreeze(anyLong(), anyString(), any(BigDecimal.class));
    }

    // --- applyFill ---
    @Test
    void applyFill_paper_delegatesToPaperAdapter() {
        balanceService.applyFill(new FillCommand(
                1L,
                true,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                null,
                null));

        verify(paperBalanceAdapter)
                .applyFill(
                        1L,
                        OrderSide.BUY,
                        "BTC/USDT",
                        new BigDecimal("0.1"),
                        new BigDecimal("50000"),
                        new BigDecimal("5"),
                        new BigDecimal("5000"));
    }

    @Test
    void applyFill_real_isNoop() {
        balanceService.applyFill(new FillCommand(
                1L,
                false,
                OrderSide.BUY,
                "BTC/USDT",
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("5"),
                new BigDecimal("5000"),
                null,
                null));

        verify(paperBalanceAdapter, never())
                .applyFill(
                        anyLong(),
                        any(OrderSide.class),
                        anyString(),
                        any(BigDecimal.class),
                        any(BigDecimal.class),
                        any(BigDecimal.class),
                        any());
    }

    // --- reset ---
    @Test
    void reset_paper_delegatesToPaperAdapter() {
        balanceService.reset(1L, true);

        verify(paperBalanceAdapter).reset(1L, "USDT");
    }

    @Test
    void reset_real_throwsIllegalArgument() {
        assertThatThrownBy(() -> balanceService.reset(1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paper");
        verify(paperBalanceAdapter, never()).reset(anyLong(), anyString());
    }
}
