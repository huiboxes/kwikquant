package com.kwikquant.account.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.types.Exchange;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExchangeAccountServiceTest {

    private ExchangeAccountMapper mapper;
    private RefreshTokenMapper refreshTokenMapper;
    private KeyManagementService keyService;
    private PaperBalanceAdapter paperBalanceAdapter;
    private QuoteCurrencyProperties quoteCurrencyProperties;
    private ExchangeAccountService service;
    private final byte[] encryptionKey = new byte[32];

    @BeforeEach
    void setUp() {
        mapper = mock(ExchangeAccountMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        keyService = mock(KeyManagementService.class);
        paperBalanceAdapter = mock(PaperBalanceAdapter.class);
        quoteCurrencyProperties = new QuoteCurrencyProperties(List.of("USDT"), new BigDecimal("100000"));
        new SecureRandom().nextBytes(encryptionKey);
        when(keyService.getCurrentKey()).thenReturn(encryptionKey);
        when(keyService.getCurrentKeyVersion()).thenReturn(1);
        // mock insert 回填 id(模拟 useGeneratedKeys),供模拟盘 create 后 initBalance(account.getId()) 用
        doAnswer(inv -> {
                    ExchangeAccount arg = inv.getArgument(0);
                    arg.setId(100L);
                    return null;
                })
                .when(mapper)
                .insert(any(ExchangeAccount.class));
        service = new ExchangeAccountService(
                mapper, refreshTokenMapper, keyService, paperBalanceAdapter, quoteCurrencyProperties);
    }

    @Test
    void createEncryptsSecret() {
        ExchangeAccount account = service.create(
                new CreateAccountCommand(1L, Exchange.BINANCE, "prod", "apiKey123", "secretXYZ", null, false));

        assertNotNull(account.getApiSecret());
        assertNotNull(account.getNonce());
        assertEquals(12, account.getNonce().length);
        assertEquals(1, account.getKeyVersion());
        assertFalse(account.isPaperTrading());
        verify(mapper).insert(any(ExchangeAccount.class));
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
        verify(paperBalanceAdapter, never()).initBalance(anyLong(), anyString());
    }

    @Test
    void createWithPassphraseUsesSeparateNonce() {
        ExchangeAccount account =
                service.create(new CreateAccountCommand(1L, Exchange.BITGET, "test", "key", "secret", "pass", false));

        assertNotNull(account.getNonce());
        assertNotNull(account.getPassphraseNonce());
        assertFalse(java.util.Arrays.equals(account.getNonce(), account.getPassphraseNonce()));
        assertFalse(account.isPaperTrading());
    }

    @Test
    void createUsesCurrentKeyVersion() {
        when(keyService.getCurrentKeyVersion()).thenReturn(3);

        ExchangeAccount account =
                service.create(new CreateAccountCommand(1L, Exchange.BINANCE, "test", "key", "secret", null, false));

        assertEquals(3, account.getKeyVersion());
    }

    @Test
    void createLiveAccount_rejectsBlankApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        new CreateAccountCommand(1L, Exchange.BINANCE, "prod", "", "secretXYZ", null, false)));
        verify(mapper, never()).insert(any(ExchangeAccount.class));
    }

    @Test
    void createLiveAccount_rejectsBlankApiSecret() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        new CreateAccountCommand(1L, Exchange.BINANCE, "prod", "apiKey123", null, null, false)));
        verify(mapper, never()).insert(any(ExchangeAccount.class));
    }

    @Test
    void createPaperAccount_rejectsExchangePaper() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new CreateAccountCommand(1L, Exchange.PAPER, "sim", null, null, null, true)));
        verify(mapper, never()).insert(any(ExchangeAccount.class));
    }

    @Test
    void createPaperAccount_allowsBlankCredentialsAndInitsBalance() {
        ExchangeAccount account =
                service.create(new CreateAccountCommand(1L, Exchange.BINANCE, "sim", null, null, null, true));

        assertNull(account.getApiKey());
        assertNull(account.getApiSecret());
        assertNull(account.getNonce());
        assertTrue(account.isPaperTrading());
        verify(mapper).insert(any(ExchangeAccount.class));
        verify(paperBalanceAdapter).initBalance(100L, "USDT");
        verify(keyService, never()).getCurrentKey(); // 模拟盘不加密
    }

    @Test
    void createLiveAccount_doesNotInitPaperBalance() {
        service.create(new CreateAccountCommand(1L, Exchange.BINANCE, "prod", "apiKey123", "secretXYZ", null, false));

        verify(paperBalanceAdapter, never()).initBalance(anyLong(), anyString());
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

    /**
     * 回归测试（H2）：update() 不传 passphrase（null）时必须保留账户原有的 passphrase 密文/nonce，
     * 而不是被 {@code encryptCredentials(apiSecret, null)} 返回的 null 覆盖清空——OKX/Bitget 账户
     * 只改 label/apiKey 而不重新携带 passphrase 是正常场景，不应静默丢失已存的 passphrase。
     */
    @Test
    void updateWithoutPassphrase_preservesExistingPassphrase() {
        byte[] originalPassphrase = new byte[] {9, 8, 7};
        byte[] originalPassphraseNonce = new byte[] {6, 5, 4};
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        a.setExchange(Exchange.OKX);
        a.setPaperTrading(false);
        a.setStatus("ACTIVE");
        a.setPassphrase(originalPassphrase);
        a.setPassphraseNonce(originalPassphraseNonce);
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.update(any(ExchangeAccount.class))).thenReturn(1);

        service.update(1L, 42L, "label", "key", "secret", null);

        assertSame(originalPassphrase, a.getPassphrase());
        assertSame(originalPassphraseNonce, a.getPassphraseNonce());
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

    @Test
    void create_rejectsDuplicateUserExchange() {
        ExchangeAccount existing = new ExchangeAccount();
        existing.setId(99L);
        existing.setUserId(1L);
        existing.setExchange(Exchange.BINANCE);
        when(mapper.findByUserAndExchange(1L, "BINANCE")).thenReturn(existing);

        com.kwikquant.shared.infra.ResourceStateConflictException ex = assertThrows(
                com.kwikquant.shared.infra.ResourceStateConflictException.class,
                () -> service.create(
                        new CreateAccountCommand(1L, Exchange.BINANCE, "dup", "key", "secret", null, false)));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(mapper, never()).insert(any(ExchangeAccount.class));
        verify(paperBalanceAdapter, never()).initBalance(anyLong(), anyString());
    }

    @Test
    void create_raceDuplicateInsert_throwsConflict() {
        when(mapper.findByUserAndExchange(anyLong(), anyString())).thenReturn(null);
        doThrow(new org.springframework.dao.DuplicateKeyException("uk_exchange_accounts_user_exchange"))
                .when(mapper)
                .insert(any(ExchangeAccount.class));

        com.kwikquant.shared.infra.ResourceStateConflictException ex = assertThrows(
                com.kwikquant.shared.infra.ResourceStateConflictException.class,
                () -> service.create(
                        new CreateAccountCommand(1L, Exchange.BINANCE, "race", "key", "secret", null, false)));
        assertTrue(ex.getMessage().contains("already exists"));
    }
}
