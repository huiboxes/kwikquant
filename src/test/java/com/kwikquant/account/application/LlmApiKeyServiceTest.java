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
import tools.jackson.databind.ObjectMapper;

/**
 * {@link LlmApiKeyService} 单元测试。
 *
 * <p>v2(tech-design §2.2 §2.5):model 单列 → available_models JSON 列表。构造注入 ObjectMapper;
 * 新增 defaultModelOf / view 坏 JSON 兜底 / create COMPATIBLE available_models 必填 校验。
 */
class LlmApiKeyServiceTest {

    private LlmApiKeyMapper mapper;
    private RefreshTokenMapper refreshTokenMapper;
    private KeyManagementService keyService;
    private LlmApiKeyService service;
    private final byte[] encryptionKey = new byte[32];
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mapper = mock(LlmApiKeyMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        keyService = mock(KeyManagementService.class);
        new SecureRandom().nextBytes(encryptionKey);
        when(keyService.getCurrentKey()).thenReturn(encryptionKey);
        when(keyService.getCurrentKeyVersion()).thenReturn(1);
        service = new LlmApiKeyService(mapper, refreshTokenMapper, keyService, objectMapper);
    }

    @Test
    void createEncryptsFullKeyAndStoresLastFour() {
        String fullKey = "sk-proj-abcdef123456";
        LlmApiKey created = service.create(1L, "My GPT Key", LlmProvider.OPENAI, fullKey, null, null);

        assertEquals(1L, created.getUserId());
        assertEquals("My GPT Key", created.getLabel());
        assertEquals(LlmProvider.OPENAI, created.getProvider());
        // api_key 字段只存末尾 4 位明文
        assertEquals("3456", created.getApiKey());
        // api_secret 存完整 key 的 AES-GCM 密文(非明文)
        assertNotNull(created.getApiSecret());
        assertNotEquals(fullKey, new String(created.getApiSecret(), StandardCharsets.UTF_8));
        // nonce 12 字节
        assertNotNull(created.getNonce());
        assertEquals(12, created.getNonce().length);
        assertEquals(1, created.getKeyVersion());
        // OPENAI 不配模型 → availableModels null(走 adapter 默认)
        assertNull(created.getAvailableModels());
        verify(mapper).insert(any(LlmApiKey.class));
    }

    @Test
    void createRoundTripsThroughDecryption() {
        String fullKey = "sk-proj-abcdef123456";
        LlmApiKey created = service.create(1L, "key", LlmProvider.OPENAI, fullKey, null, null);

        // KMS 用真实加密流程解密(非 mock 返回值),验证密文可还原
        byte[] plain = ApiKeyEncryptor.decrypt(created.getApiSecret(), encryptionKey, created.getNonce());
        assertEquals(fullKey, new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    void createOpenAiCompatibleWithoutBaseUrlThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        1L, "compat", LlmProvider.OPENAI_COMPATIBLE, "sk-x123456", null, List.of("deepseek-chat")));
        assertTrue(ex.getMessage().toLowerCase().contains("baseurl"));
    }

    @Test
    void createOpenAiCompatibleWithBaseUrlSucceeds() {
        LlmApiKey created = service.create(
                1L,
                "compat",
                LlmProvider.OPENAI_COMPATIBLE,
                "sk-x123456",
                "https://gw.example.com/v1",
                List.of("deepseek-chat"));
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, created.getProvider());
        assertEquals("https://gw.example.com/v1", created.getBaseUrl());
    }

    @Test
    void createShortKeyStoresAvailableTail() {
        // 短 key(<4 字符)也要能存,取实际可用末尾
        LlmApiKey created = service.create(1L, "short", LlmProvider.OPENAI, "ab", null, null);
        assertEquals("ab", created.getApiKey());
    }

    @Test
    void createWithDuplicateLabelThrowsIllegalArg() {
        doThrow(new DataIntegrityViolationException("duplicate label"))
                .when(mapper)
                .insert(any(LlmApiKey.class));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(1L, "dup", LlmProvider.OPENAI, "sk-proj-123456", null, null));
        assertTrue(ex.getMessage().toLowerCase().contains("label"));
    }

    @Test
    void create_withAvailableModels_shouldPersistJson() {
        // v2: available_models JSON 持久化(供 defaultModelOf / view 反序列化)
        LlmApiKey created = service.create(
                1L,
                "compat",
                LlmProvider.OPENAI_COMPATIBLE,
                "sk-x123456",
                "https://gw.example.com/v1",
                List.of("deepseek-chat", "deepseek-r1"));
        // domain 存 raw JSON String
        assertEquals("[\"deepseek-chat\",\"deepseek-r1\"]", created.getAvailableModels());
        // view 反序列化成 List
        assertEquals(
                List.of("deepseek-chat", "deepseek-r1"), service.view(created).availableModels());
    }

    @Test
    void create_openAiCompatibleWithoutAvailableModels_shouldThrow() {
        // v2: OPENAI_COMPATIBLE 无统一默认 model,available_models 必填 ≥1(Service 层校验,非 @Size)
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        1L, "compat", LlmProvider.OPENAI_COMPATIBLE, "sk-x123456", "https://gw.example.com/v1", null));
        assertTrue(ex.getMessage().toLowerCase().contains("available_models"));
    }

    @Test
    void create_openAiCompatibleWithEmptyAvailableModels_shouldThrow() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(
                        1L,
                        "compat",
                        LlmProvider.OPENAI_COMPATIBLE,
                        "sk-x123456",
                        "https://gw.example.com/v1",
                        List.of()));
        assertTrue(ex.getMessage().toLowerCase().contains("available_models"));
    }

    @Test
    void defaultModelOf_returnsFirstAvailable() {
        LlmApiKey key = new LlmApiKey();
        key.setAvailableModels("[\"gpt-5.6\",\"gpt-5-mini\"]");
        assertEquals("gpt-5.6", service.defaultModelOf(key));
    }

    @Test
    void defaultModelOf_nullAvailable_returnsNull() {
        LlmApiKey key = new LlmApiKey();
        key.setAvailableModels(null);
        assertNull(service.defaultModelOf(key));
    }

    @Test
    void defaultModelOf_emptyArray_returnsNull() {
        LlmApiKey key = new LlmApiKey();
        key.setAvailableModels("[]");
        assertNull(service.defaultModelOf(key));
    }

    @Test
    void defaultModelOf_malformedJson_returnsNull() {
        // 坏 JSON 兜底返 null,不抛(防 DB 脏数据致 chat 500)
        LlmApiKey key = new LlmApiKey();
        key.setAvailableModels("not-json");
        assertNull(service.defaultModelOf(key));
    }

    @Test
    void view_malformedJson_returnsEmptyList() {
        // 坏 JSON 兜底返空 list,不致 list 端点 500
        LlmApiKey key = new LlmApiKey();
        key.setProvider(LlmProvider.OPENAI);
        key.setApiKey("1234");
        key.setAvailableModels("not-json");
        assertTrue(service.view(key).availableModels().isEmpty());
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
        // 脱敏:provider 前缀 + ... + 末尾4位(不暴露完整 key)
        assertEquals("sk-proj...3456", v.apiKeyMasked());
        // available_models null → 空列表
        assertTrue(v.availableModels().isEmpty());
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
        // 覆盖 maskApiKey 里 OPENAI_COMPATIBLE 分支(DeepSeek/Ollama 等)
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
        LlmApiKey key = service.create(1L, "k", LlmProvider.OPENAI, "sk-proj-abcdef123456", null, null);
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
        // Round 3 修:mapper.deleteByIdAndUser 返回 0 → Service 抛 4009 而非静默返回
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
        // product-direction §11.2:LLM API key 新增必须撤销活动 RefreshToken
        service.create(1L, "k", LlmProvider.OPENAI, "sk-proj-abc", null, null);
        verify(refreshTokenMapper).revokeAllByUserId(1L);
    }

    // ─── update(tech-design v2 follow-up:D3 apiKey 留空不改 / D4 provider 不可变) ───

    @Test
    void update_changesLabelAndModels_withoutRotatingKey() {
        // apiKey 留空 → 不重新加密、不撤销会话(只改 label/models)
        LlmApiKey key = existingKey(1L, 42L, LlmProvider.OPENAI);
        when(mapper.findById(1L)).thenReturn(key);
        when(mapper.update(any(LlmApiKey.class))).thenReturn(1);

        var view = service.update(1L, 42L, "new label", null, null, List.of("gpt-5.6", "gpt-5-mini"));

        assertEquals("new label", view.label());
        assertEquals(List.of("gpt-5.6", "gpt-5-mini"), view.availableModels());
        // api_key masked 保持原末4位(未轮换)
        assertEquals("3456", key.getApiKey());
        // view 返回的 masked 也保持原末4位(M2:防 maskApiKey provider 前缀分支错)
        assertEquals("sk-proj...3456", view.apiKeyMasked());
        verify(keyService, never()).getCurrentKey();
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void update_rotatesKeyWhenApiKeyProvided() {
        // apiKey 非空 → 重新加密 + 更新 masked + revoke(密钥轮换)
        LlmApiKey key = existingKey(1L, 42L, LlmProvider.OPENAI);
        when(mapper.findById(1L)).thenReturn(key);
        when(mapper.update(any(LlmApiKey.class))).thenReturn(1);

        var view = service.update(1L, 42L, "label", "sk-proj-new9999", null, null);

        // masked 更新为新 key 末4位
        assertEquals("9999", key.getApiKey());
        // view 返回的 masked 也更新为新末4位(M2)
        assertEquals("sk-proj...9999", view.apiKeyMasked());
        // 轮换触发撤销会话
        verify(refreshTokenMapper).revokeAllByUserId(42L);
        verify(keyService).getCurrentKey();
    }

    @Test
    void update_rotatesKey_roundTripsDecryption() {
        // 轮换后密文可解密还原(KMS 真实加密流程,非 mock 返回值)
        LlmApiKey key = existingKey(1L, 42L, LlmProvider.OPENAI);
        when(mapper.findById(1L)).thenReturn(key);
        when(mapper.update(any(LlmApiKey.class))).thenReturn(1);

        service.update(1L, 42L, "label", "sk-proj-rotated", null, null);

        byte[] plain = ApiKeyEncryptor.decrypt(key.getApiSecret(), encryptionKey, key.getNonce());
        assertEquals("sk-proj-rotated", new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    void update_compatibleWithoutBaseUrl_throws() {
        LlmApiKey key = existingKey(1L, 42L, LlmProvider.OPENAI_COMPATIBLE);
        when(mapper.findById(1L)).thenReturn(key);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(1L, 42L, "label", null, null, List.of("deepseek-chat")));
        assertTrue(ex.getMessage().toLowerCase().contains("baseurl"));
        verify(mapper, never()).update(any(LlmApiKey.class));
    }

    @Test
    void update_compatibleWithoutAvailableModels_throws() {
        LlmApiKey key = existingKey(1L, 42L, LlmProvider.OPENAI_COMPATIBLE);
        when(mapper.findById(1L)).thenReturn(key);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(1L, 42L, "label", null, "https://gw.example.com/v1", null));
        assertTrue(ex.getMessage().toLowerCase().contains("available_models"));
        verify(mapper, never()).update(any(LlmApiKey.class));
    }

    @Test
    void update_duplicateLabel_throwsIllegalArg() {
        LlmApiKey key = existingKey(1L, 42L, LlmProvider.OPENAI);
        when(mapper.findById(1L)).thenReturn(key);
        doThrow(new DataIntegrityViolationException("duplicate label"))
                .when(mapper)
                .update(any(LlmApiKey.class));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> service.update(1L, 42L, "dup", null, null, null));
        assertTrue(ex.getMessage().toLowerCase().contains("label"));
    }

    @Test
    void update_deepDefenseFails_throwsConflict() {
        // mapper.update 返回 0 → 4009(并发 owner 变更),且不撤销会话
        LlmApiKey key = existingKey(1L, 42L, LlmProvider.OPENAI);
        when(mapper.findById(1L)).thenReturn(key);
        when(mapper.update(any(LlmApiKey.class))).thenReturn(0);

        assertThrows(
                com.kwikquant.shared.infra.ResourceStateConflictException.class,
                () -> service.update(1L, 42L, "label", null, null, null));
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
    }

    @Test
    void update_notOwner_throws() {
        LlmApiKey key = existingKey(1L, 99L, LlmProvider.OPENAI);
        when(mapper.findById(1L)).thenReturn(key);

        assertThrows(OwnershipViolationException.class, () -> service.update(1L, 42L, "label", null, null, null));
        verify(mapper, never()).update(any(LlmApiKey.class));
        verify(refreshTokenMapper, never()).revokeAllByUserId(anyLong());
    }

    /** 构造一个已存在的 key 实体(模拟 DB 取出),含原 masked/密文/nonce/keyVersion。 */
    private LlmApiKey existingKey(long id, long userId, LlmProvider provider) {
        LlmApiKey key = new LlmApiKey();
        key.setId(id);
        key.setUserId(userId);
        key.setProvider(provider);
        key.setApiKey("3456");
        key.setApiSecret(new byte[] {1, 2, 3});
        key.setNonce(new byte[12]);
        key.setKeyVersion(1);
        key.setAvailableModels("[\"gpt-5.6\"]");
        return key;
    }
}
