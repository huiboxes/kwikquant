package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BalanceServiceTest {

    private ExchangeAccountService accountService;
    private KeyManagementService keyManagementService;
    private BalanceService balanceService;

    @BeforeEach
    void setUp() {
        accountService = mock(ExchangeAccountService.class);
        keyManagementService = mock(KeyManagementService.class);
        balanceService = new BalanceService(accountService, keyManagementService);
    }

    @Test
    void fetchBalance_paperAccount_returnsMockBalance() {
        ExchangeAccount paper = new ExchangeAccount();
        paper.setId(1L);
        paper.setUserId(42L);
        paper.setExchange(Exchange.PAPER);
        when(accountService.getOwned(1L, 42L)).thenReturn(paper);

        BalanceSnapshot snapshot = balanceService.fetchBalance(1L, 42L);

        assertThat(snapshot.currencies()).containsKey("USDT");
        assertThat(snapshot.currencies().get("USDT").total()).isEqualByComparingTo("100000");
        verify(keyManagementService, never()).decryptSecret(any());
    }

    @Test
    void fetchBalance_realExchange_throwsOnApiError() {
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
}
