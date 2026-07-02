package com.kwikquant.account.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.account.infrastructure.LlmApiKeyMapper;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.types.LlmProvider;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class LlmApiKeyServiceTest {

    private LlmApiKeyMapper mapper;
    private RefreshTokenMapper refreshTokenMapper;
    private KeyManagementService keyService;
    private LlmApiKeyService service;
    private final byte[] encryptionKey = new byte[32];

    @BeforeEach
    void setUp() {
        mapper = mock(LlmApiKeyMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        keyService = mock(KeyManagementService.class);
        new SecureRandom().nextBytes(encryptionKey);
        when(keyService.getCurrentKey()).thenReturn(encryptionKey);
        when(keyService.getCurrentKeyVersion()).thenReturn(1);
        service = new LlmApiKeyService(mapper, refreshTokenMapper, keyService);
    }

    @Test
    void createEncryptsFullKeyAndStoresLastFour() {
        String fullKey = "sk-proj-abcdef123456";
        LlmApiKey created = service.create(1L, "My GPT Key", LlmProvider.OPENAI, fullKey, null);

        assertEquals(1L, created.getUserId());
        assertEquals("My GPT Key", created.getLabel());
        assertEquals(LlmProvider.OPENAI, created.getProvider());
        // api_key 字段只存末尾 4 位明文
        assertEquals("3456", created.getApiKey());
        // api_secret 存完整 key 的 AES-GCM 密文（非明文）
        assertNotNull(created.getApiSecret());
        assertNotEquals(fullKey, new String(created.getApiSecret(), StandardCharsets.UTF_8));
        // nonce 12 字节
        assertNotNull(created.getNonce());
        assertEquals(12, created.getNonce().length);
        assertEquals(1, created.getKeyVersion());
        verify(mapper).insert(any(LlmApiKey.class));
    }

    @Test
    void createRoundTripsThroughDecryption() {
        String fullKey = "sk-proj-abcdef123456";
        LlmApiKey created = service.create(1L, "key", LlmProvider.OPENAI, fullKey, null);

        // KMS 用真实加密流程解密（非 mock 返回值），验证密文可还原
        byte[] plain = ApiKeyEncryptor.decrypt(created.getApiSecret(), encryptionKey, created.getNonce());
        assertEquals(fullKey, new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    void createOpenAiCompatibleWithoutBaseUrlThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(1L, "compat", LlmProvider.OPENAI_COMPATIBLE, "sk-x123456", null));
        assertTrue(ex.getMessage().toLowerCase().contains("baseurl"));
    }

    @Test
    void createOpenAiCompatibleWithBaseUrlSucceeds() {
        LlmApiKey created =
                service.create(1L, "compat", LlmProvider.OPENAI_COMPATIBLE, "sk-x123456", "https://gw.example.com/v1");
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, created.getProvider());
        assertEquals("https://gw.example.com/v1", created.getBaseUrl());
    }

    @Test
    void createShortKeyStoresAvailableTail() {
        // 短 key（<4 字符）也要能存，取实际可用末尾
        LlmApiKey created = service.create(1L, "short", LlmProvider.OPENAI, "ab", null);
        assertEquals("ab", created.getApiKey());
    }

    @Test
    void createWithDuplicateLabelThrowsIllegalArg() {
        doThrow(new DataIntegrityViolationException("duplicate label"))
                .when(mapper)
                .insert(any(LlmApiKey.class));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(1L, "dup", LlmProvider.OPENAI, "sk-proj-123456", null));
        assertTrue(ex.getMessage().toLowerCase().contains("label"));
    }

    @Test
    void listByUserReturnsMaskedViews() {
        LlmApiKey a = new LlmApiKey();
        a.setId(1L);
        a.setLabel("My GPT Key");
        a.setProvider(LlmProvider.OPENAI);
        a.setApiKey("3456");
        a.setBaseUrl(null);
        when(mapper.findByUserId(1L)).thenReturn(List.of(a));

        List<LlmApiKeyService.LlmApiKeyView> views = service.listByUser(1L);

        assertEquals(1, views.size());
        LlmApiKeyService.LlmApiKeyView v = views.getFirst();
        assertEquals(1L, v.id());
        assertEquals("My GPT Key", v.label());
        assertEquals(LlmProvider.OPENAI, v.provider());
        // 脱敏：provider 前缀 + ... + 末尾4位（不暴露完整 key）
        assertEquals("sk-proj...3456", v.apiKeyMasked());
        verify(mapper, never()).findById(anyLong());
    }

    @Test
    void listByUserMasksAnthropicDifferently() {
        LlmApiKey a = new LlmApiKey();
        a.setId(2L);
        a.setLabel("claude");
        a.setProvider(LlmProvider.ANTHROPIC);
        a.setApiKey("9abc");
        when(mapper.findByUserId(1L)).thenReturn(List.of(a));

        List<LlmApiKeyService.LlmApiKeyView> views = service.listByUser(1L);
        assertEquals("sk-ant...9abc", views.getFirst().apiKeyMasked());
    }

    @Test
    void listByUserMasksOpenAiCompatibleAsGenericSk() {
        // 覆盖 maskApiKey 里 OPENAI_COMPATIBLE 分支（DeepSeek/Ollama 等）
        LlmApiKey a = new LlmApiKey();
        a.setId(3L);
        a.setLabel("compat");
        a.setProvider(LlmProvider.OPENAI_COMPATIBLE);
        a.setApiKey("d123");
        a.setBaseUrl("https://api.deepseek.com/v1");
        when(mapper.findByUserId(1L)).thenReturn(List.of(a));

        List<LlmApiKeyService.LlmApiKeyView> views = service.listByUser(1L);
        assertEquals("sk...d123", views.getFirst().apiKeyMasked());
    }

    @Test
    void getOwnedReturnsWhenOwner() {
        LlmApiKey key = new LlmApiKey();
        key.setId(1L);
        key.setUserId(42L);
        when(mapper.findById(1L)).thenReturn(key);

        LlmApiKey result = service.getOwned(1L, 42L);
        assertEquals(1L, result.getId());
    }

    @Test
    void getOwnedThrows404WhenNotFound() {
        when(mapper.findById(999L)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.getOwned(999L, 42L));
    }

    @Test
    void getOwnedThrows403WhenNotOwner() {
        LlmApiKey key = new LlmApiKey();
        key.setId(1L);
        key.setUserId(99L);
        when(mapper.findById(1L)).thenReturn(key);
        assertThrows(OwnershipViolationException.class, () -> service.getOwned(1L, 42L));
    }

    @Test
    void decryptSecretReturnsFullKeyString() {
        LlmApiKey key = service.create(1L, "k", LlmProvider.OPENAI, "sk-proj-abcdef123456", null);
        // mock KMS.decryptSecret(LlmApiKey) 返回真实密文解密结果
        byte[] plain = ApiKeyEncryptor.decrypt(key.getApiSecret(), encryptionKey, key.getNonce());
        when(keyService.decryptSecret(key)).thenReturn(plain);

        String decrypted = service.decryptSecret(key);
        assertEquals("sk-proj-abcdef123456", decrypted);
    }

    @Test
    void deleteRemovesKeyWhenOwner() {
        LlmApiKey key = new LlmApiKey();
        key.setId(1L);
        key.setUserId(42L);
        when(mapper.findById(1L)).thenReturn(key);

        when(mapper.deleteByIdAndUser(1L, 42L)).thenReturn(1);

        service.delete(1L, 42L);
        verify(mapper).deleteByIdAndUser(1L, 42L);
        verify(refreshTokenMapper).revokeAllByUserId(42L); // 与 ExchangeAccount 对齐
    }

    @Test
    void deleteDeepDefenseFails_throwsConflict() {
        // Round 3 修：mapper.deleteByIdAndUser 返回 0 → Service 抛 4009 而非静默返回
        LlmApiKey key = new LlmApiKey();
        key.setId(1L);
        key.setUserId(42L);
        when(mapper.findById(1L)).thenReturn(key);
        when(mapper.deleteByIdAndUser(1L, 42L)).thenReturn(0);

        com.kwikquant.shared.infra.ResourceStateConflictException ex = assertThrows(
                com.kwikquant.shared.infra.ResourceStateConflictException.class, () -> service.delete(1L, 42L));
        assertTrue(ex.getMessage().contains("llm_api_key"), "message should contain resource type");
        assertTrue(ex.getMessage().contains("1"), "message should contain resource id");
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void deleteThrowsWhenNotOwner() {
        LlmApiKey key = new LlmApiKey();
        key.setId(1L);
        key.setUserId(99L);
        when(mapper.findById(1L)).thenReturn(key);

        assertThrows(OwnershipViolationException.class, () -> service.delete(1L, 42L));
        verify(mapper, never()).deleteByIdAndUser(anyLong(), anyLong());
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void createAlsoRevokesRefreshTokens() {
        // product-direction §11.2：LLM API key 新增必须撤销活动 RefreshToken
        service.create(1L, "k", LlmProvider.OPENAI, "sk-proj-abc", null);
        verify(refreshTokenMapper).revokeAllByUserId(1L);
    }
}
