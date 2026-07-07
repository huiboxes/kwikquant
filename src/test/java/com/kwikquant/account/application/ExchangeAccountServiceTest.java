package com.kwikquant.account.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.types.Exchange;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExchangeAccountServiceTest {

    private ExchangeAccountMapper mapper;
    private RefreshTokenMapper refreshTokenMapper;
    private KeyManagementService keyService;
    private PaperBalanceAdapter paperBalanceAdapter;
    private ExchangeAccountService service;
    private final byte[] encryptionKey = new byte[32];

    @BeforeEach
    void setUp() {
        mapper = mock(ExchangeAccountMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        keyService = mock(KeyManagementService.class);
        paperBalanceAdapter = mock(PaperBalanceAdapter.class);
        new SecureRandom().nextBytes(encryptionKey);
        when(keyService.getCurrentKey()).thenReturn(encryptionKey);
        when(keyService.getCurrentKeyVersion()).thenReturn(1);
        // mock insert 回填 id(模拟 useGeneratedKeys),供 PAPER create 后 initBalance(account.getId()) 用
        doAnswer(inv -> {
                    ExchangeAccount arg = inv.getArgument(0);
                    arg.setId(100L);
                    return null;
                })
                .when(mapper)
                .insert(any(ExchangeAccount.class));
        service = new ExchangeAccountService(mapper, refreshTokenMapper, keyService, paperBalanceAdapter);
    }

    @Test
    void createEncryptsSecret() {
        ExchangeAccount account = service.create(1L, Exchange.BINANCE, "prod", "apiKey123", "secretXYZ", null, null);

        assertNotNull(account.getApiSecret());
        assertNotNull(account.getNonce());
        assertEquals(12, account.getNonce().length);
        assertEquals(1, account.getKeyVersion());
        assertFalse(account.isPaperTrading()); // 真实交易所 paperTrading=false
        verify(mapper).insert(any(ExchangeAccount.class));
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
        verify(paperBalanceAdapter, never()).initBalance(anyLong());
    }

    @Test
    void createWithPassphraseUsesSeparateNonce() {
        ExchangeAccount account = service.create(1L, Exchange.BITGET, "test", "key", "secret", "pass", null);

        assertNotNull(account.getNonce());
        assertNotNull(account.getPassphraseNonce());
        assertFalse(java.util.Arrays.equals(account.getNonce(), account.getPassphraseNonce()));
        assertFalse(account.isPaperTrading());
    }

    @Test
    void createUsesCurrentKeyVersion() {
        when(keyService.getCurrentKeyVersion()).thenReturn(3);

        ExchangeAccount account = service.create(1L, Exchange.BINANCE, "test", "key", "secret", null, null);

        assertEquals(3, account.getKeyVersion());
    }

    @Test
    void create_paper_setsReferenceExchangePaperTradingAndInitBalance() {
        ExchangeAccount account =
                service.create(1L, Exchange.PAPER, "paper-binance", "paper-key", null, null, Exchange.BINANCE);

        assertEquals(Exchange.BINANCE, account.getReferenceExchange());
        assertTrue(account.isPaperTrading());
        // PAPER 跳过加密:apiSecret 空 byte[],nonce/passphraseNonce null
        assertNotNull(account.getApiSecret());
        assertEquals(0, account.getApiSecret().length);
        assertNull(account.getNonce());
        assertNull(account.getPassphraseNonce());
        assertNull(account.getPassphrase());
        verify(paperBalanceAdapter).initBalance(100L);
        verify(keyService, never()).getCurrentKey(); // PAPER 不加密
    }

    @Test
    void create_paperWithoutReferenceExchange_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(1L, Exchange.PAPER, "paper", "paper-key", null, null, null));
    }

    @Test
    void create_real_setsReferenceExchangeNull() {
        ExchangeAccount account = service.create(1L, Exchange.OKX, "real-okx", "key", "secret", "pass", null);

        assertNull(account.getReferenceExchange()); // 真实交易所 referenceExchange=null
        assertFalse(account.isPaperTrading());
    }

    @Test
    void listByUser() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setExchange(Exchange.BINANCE);
        a.setLabel("test");
        a.setApiKey("key");
        a.setPaperTrading(true);
        a.setStatus("ACTIVE");
        when(mapper.findByUserId(42L)).thenReturn(List.of(a));

        var views = service.listByUser(42L);
        assertEquals(1, views.size());
        assertEquals("test", views.getFirst().label());
    }

    @Test
    void getOwnedReturnsWhenOwner() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        when(mapper.findById(1L)).thenReturn(a);

        ExchangeAccount result = service.getOwned(1L, 42L);
        assertEquals(1L, result.getId());
    }

    @Test
    void getOwnedThrows404WhenNotFound() {
        when(mapper.findById(999L)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.getOwned(999L, 42L));
    }

    @Test
    void getOwnedThrows403WhenNotOwner() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(99L);
        when(mapper.findById(1L)).thenReturn(a);
        assertThrows(OwnershipViolationException.class, () -> service.getOwned(1L, 42L));
    }

    @Test
    void deleteRevokesTokens() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.deleteByIdAndUser(1L, 42L)).thenReturn(1);

        service.delete(1L, 42L);

        verify(mapper).deleteByIdAndUser(1L, 42L);
        verify(refreshTokenMapper).revokeAllByUserId(42L);
    }

    @Test
    void updateReEncryptsAndRevokesTokens() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        a.setExchange(Exchange.BINANCE);
        a.setPaperTrading(true);
        a.setStatus("ACTIVE");
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.update(any(ExchangeAccount.class))).thenReturn(1);

        var view = service.update(1L, 42L, "new-label", "newKey", "newSecret", "newPass");

        assertEquals("new-label", view.label());
        assertEquals("newKey", view.apiKey());
        verify(mapper).update(any(ExchangeAccount.class));
        verify(refreshTokenMapper).revokeAllByUserId(42L);
    }

    @Test
    void updateWithoutPassphrase() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        a.setExchange(Exchange.BINANCE);
        a.setPaperTrading(false);
        a.setStatus("ACTIVE");
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.update(any(ExchangeAccount.class))).thenReturn(1);

        var view = service.update(1L, 42L, "label", "key", "secret", null);

        assertNotNull(view);
        assertFalse(view.paperTrading());
    }

    @Test
    void deleteDeepDefenseFails_throwsConflict() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.deleteByIdAndUser(1L, 42L)).thenReturn(0);

        com.kwikquant.shared.infra.ResourceStateConflictException ex = assertThrows(
                com.kwikquant.shared.infra.ResourceStateConflictException.class, () -> service.delete(1L, 42L));
        assertTrue(ex.getMessage().contains("exchange_account"), "message should contain resource type");
        assertTrue(ex.getMessage().contains("1"), "message should contain resource id");
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void updateDeepDefenseFails_throwsConflict() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        a.setExchange(Exchange.BINANCE);
        a.setPaperTrading(true);
        a.setStatus("ACTIVE");
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.update(any(ExchangeAccount.class))).thenReturn(0);

        com.kwikquant.shared.infra.ResourceStateConflictException ex = assertThrows(
                com.kwikquant.shared.infra.ResourceStateConflictException.class,
                () -> service.update(1L, 42L, "label", "key", "secret", null));
        assertTrue(ex.getMessage().contains("exchange_account"), "message should contain resource type");
        assertTrue(ex.getMessage().contains("1"), "message should contain resource id");
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
    }
}
