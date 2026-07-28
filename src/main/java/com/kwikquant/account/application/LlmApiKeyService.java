package com.kwikquant.account.application;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.account.infrastructure.LlmApiKeyMapper;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.shared.infra.Auditable;
import com.kwikquant.shared.infra.OwnershipCheck;
import com.kwikquant.shared.types.LlmProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM API 密钥管理服务。
 *
 * <p>加密架构与 {@link ExchangeAccountService} 一致:完整 key 经 AES-256-GCM 加密存 {@code api_secret},
 * {@code api_key} 字段只存末尾 4 位明文(仅列表识别用,不含 secret 高熵部分)。
 *
 * <p><b>v2(tech-design §2.2)</b>:{@code model} 单列 → {@code available_models} JSON 列表(一个 key 多模型)。
 * domain 存 raw JSON String,mapper 纯 String 读写,Service 层用 {@link ObjectMapper} 序列化/反序列化,零 TypeHandler。
 * {@code view()} 坏 JSON 兜底返空 list(不致 list 端点 500);{@code defaultModelOf} 供 AiChatService fallback。
 *
 * <p><b>与 tech-design §3.7 的偏差(架构师决策)</b>:{@code delete} 签名改为 {@code delete(keyId, userId)}
 * 以强制所有权校验(参照 {@link ExchangeAccountService#delete});{@code apiKeyMasked} 按 provider 区分前缀。
 */
@Service
public class LlmApiKeyService {

    private final LlmApiKeyMapper mapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final KeyManagementService keyService;
    private final ObjectMapper objectMapper;

    public LlmApiKeyService(
            LlmApiKeyMapper mapper,
            RefreshTokenMapper refreshTokenMapper,
            KeyManagementService keyService,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.keyService = keyService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Auditable(action = "LLM_KEY_CREATED", targetType = "llm_api_key", targetId = "#label")
    public LlmApiKey create(
            long userId,
            String label,
            LlmProvider provider,
            String apiKey,
            String baseUrl,
            List<String> availableModels) {
        if (provider == LlmProvider.OPENAI_COMPATIBLE && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalArgumentException("baseUrl is required for OPENAI_COMPATIBLE provider");
        }
        // COMPATIBLE 无统一默认 model(adapter defaultModel() 返 null → Flux.error(0) → sanitize 提示),
        // 必须在 key 级配 ≥1 个模型。OPENAI/ANTHROPIC 可不配(null 走 adapter gpt-4o / claude-sonnet-4-20250514)。
        if (provider == LlmProvider.OPENAI_COMPATIBLE && (availableModels == null || availableModels.isEmpty())) {
            throw new IllegalArgumentException("available_models is required for OPENAI_COMPATIBLE provider");
        }
        byte[] masterKey = keyService.getCurrentKey();
        int keyVersion = keyService.getCurrentKeyVersion();
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        byte[] cipher = ApiKeyEncryptor.encrypt(apiKey.getBytes(StandardCharsets.UTF_8), masterKey, nonce);

        LlmApiKey entity = new LlmApiKey();
        entity.setUserId(userId);
        entity.setLabel(label);
        entity.setProvider(provider);
        entity.setApiKey(lastFour(apiKey));
        entity.setApiSecret(cipher);
        entity.setNonce(nonce);
        entity.setKeyVersion(keyVersion);
        entity.setBaseUrl(baseUrl);
        entity.setAvailableModels(serializeModels(availableModels));
        try {
            mapper.insert(entity);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Label already exists for this user", e);
        }
        // 与 ExchangeAccountService 对齐(product-direction §11.2):密钥新增/删除必须撤销活动 RefreshToken。
        refreshTokenMapper.revokeAllByUserId(userId);
        return entity;
    }

    public List<LlmApiKeyView> listByUser(long userId) {
        return mapper.findByUserId(userId).stream().map(this::view).toList();
    }

    /** 把实体转为脱敏视图(不暴露 api_secret/nonce)。供 Controller 构造响应用。 */
    public LlmApiKeyView view(LlmApiKey entity) {
        return new LlmApiKeyView(
                entity.getId(),
                entity.getLabel(),
                entity.getProvider(),
                maskApiKey(entity),
                entity.getBaseUrl(),
                parseModels(entity.getAvailableModels()),
                entity.getCreatedAt());
    }

    public LlmApiKey getOwned(long keyId, long userId) {
        LlmApiKey key = mapper.findById(keyId);
        return OwnershipCheck.requireOwned(key, key == null ? 0 : key.getUserId(), userId, "llm_api_key");
    }

    /**
     * 解密完整 LLM secret。仅 {@code AiChatService} 内部调用,不暴露 REST。
     */
    public String decryptSecret(LlmApiKey key) {
        byte[] plain = keyService.decryptSecret(key);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * 反序列化 {@code available_models} 取首项;null/空/坏 JSON 返 null。供 AiChatService fallback
     * (tech-design §2.3:request.model() > defaultModelOf(key) > adapter.defaultModel())。
     */
    public String defaultModelOf(LlmApiKey key) {
        List<String> models = parseModels(key.getAvailableModels());
        return models.isEmpty() ? null : models.get(0);
    }

    /**
     * 反序列化 available_models JSON 成 List。null/空/坏 JSON 返空 list(兜底,不致 list 端点 500)。
     */
    List<String> parseModels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                result.add(child.asText());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 序列化 List 成 JSON String;null/空 list 返 null(OPENAI/ANTHROPIC 不配走 adapter 默认)。 */
    private String serializeModels(List<String> availableModels) {
        if (availableModels == null || availableModels.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(availableModels);
        } catch (Exception e) {
            // 不该发生(List<String> 序列化稳定),抛 IllegalStateException 暴露 bug
            throw new IllegalStateException("Failed to serialize available_models", e);
        }
    }

    @Transactional
    @Auditable(action = "LLM_KEY_DELETED", targetType = "llm_api_key", targetId = "#keyId")
    public void delete(long keyId, long userId) {
        LlmApiKey key = getOwned(keyId, userId);
        // 深度防御消费:deleteByIdAndUser WHERE 含 user_id,返回 0 = 并发已删或 owner 变更
        int deleted = mapper.deleteByIdAndUser(key.getId(), userId);
        if (deleted == 0) {
            throw new com.kwikquant.shared.infra.ResourceStateConflictException("llm_api_key " + keyId);
        }
        // 与 ExchangeAccountService 对齐(product-direction §11.2):密钥删除必须撤销活动 RefreshToken。
        refreshTokenMapper.revokeAllByUserId(userId);
    }

    private static String lastFour(String apiKey) {
        if (apiKey == null) {
            return "";
        }
        int len = apiKey.length();
        return len <= 4 ? apiKey : apiKey.substring(len - 4);
    }

    private static String maskApiKey(LlmApiKey key) {
        String prefix =
                switch (key.getProvider()) {
                    case OPENAI -> "sk-proj";
                    case ANTHROPIC -> "sk-ant";
                    case OPENAI_COMPATIBLE -> "sk";
                };
        return prefix + "..." + key.getApiKey();
    }

    public record LlmApiKeyView(
            @io.swagger.v3.oas.annotations.media.Schema(description = "密钥 ID", example = "42") Long id,
            @io.swagger.v3.oas.annotations.media.Schema(description = "密钥标签", example = "主 GPT key") String label,
            @io.swagger.v3.oas.annotations.media.Schema(description = "LLM 提供商", example = "OPENAI")
                    LlmProvider provider,
            @io.swagger.v3.oas.annotations.media.Schema(description = "API key 末尾 4 位明文,用于识别展示", example = "...6xyz")
                    String apiKeyMasked,
            @io.swagger.v3.oas.annotations.media.Schema(
                            description = "自定义 base URL,无则 null",
                            example = "https://api.example.com/v1")
                    String baseUrl,
            @io.swagger.v3.oas.annotations.media.Schema(
                            description = "该 key 配的偏好模型列表;COMPATIBLE 必填,OPENAI/ANTHROPIC 可空",
                            example = "[\"gpt-5.6\",\"gpt-5-mini\"]")
                    List<String> availableModels,
            @io.swagger.v3.oas.annotations.media.Schema(description = "创建时间", example = "2026-07-04T12:00:00Z")
                    Instant createdAt) {}
}
