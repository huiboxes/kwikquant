package com.kwikquant.account.domain;

import com.kwikquant.shared.types.LlmProvider;
import java.time.Instant;

/**
 * LLM API 密钥实体。
 *
 * <p>对 OpenAI/Anthropic 单 key 模型（{@code sk-xxx} 既是标识也是 secret），{@code apiKey} 字段只存末尾 4 位
 * 明文（仅用于列表识别，不含 secret 高熵部分），完整 key 加密存 {@code apiSecret}（AES-256-GCM）。
 * 加密架构与 {@link ExchangeAccount} 一致：per-record nonce + 版本化 master key。
 */
public final class LlmApiKey {

    private Long id;
    private long userId;
    private String label;
    private LlmProvider provider;
    private String apiKey;
    private byte[] apiSecret;
    private byte[] nonce;
    private int keyVersion;
    private String baseUrl;
    private Instant createdAt;
    private Instant updatedAt;

    public LlmApiKey() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LlmProvider getProvider() {
        return provider;
    }

    public void setProvider(LlmProvider provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public byte[] getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(byte[] apiSecret) {
        this.apiSecret = apiSecret;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
