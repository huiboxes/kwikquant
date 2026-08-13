package com.kwikquant.account.domain;

import com.kwikquant.shared.types.Exchange;
import java.time.Instant;

public final class ExchangeAccount {

    private Long id;
    private long userId;
    private Exchange exchange;
    private String label;
    private byte[] apiKeyCiphertext;
    private byte[] apiKeyNonce;
    private Integer apiKeyKeyVersion;
    private byte[] apiSecretCiphertext;
    private byte[] apiSecretNonce;
    private Integer apiSecretKeyVersion;
    private byte[] passphraseCiphertext;
    private byte[] passphraseEncryptionNonce;
    private Integer passphraseKeyVersion;
    private boolean paperTrading;
    private boolean testnet;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public ExchangeAccount() {}

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

    public Exchange getExchange() {
        return exchange;
    }

    public void setExchange(Exchange exchange) {
        this.exchange = exchange;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public byte[] getApiKeyCiphertext() {
        return apiKeyCiphertext;
    }

    public void setApiKeyCiphertext(byte[] apiKeyCiphertext) {
        this.apiKeyCiphertext = apiKeyCiphertext;
    }

    public byte[] getApiKeyNonce() {
        return apiKeyNonce;
    }

    public void setApiKeyNonce(byte[] apiKeyNonce) {
        this.apiKeyNonce = apiKeyNonce;
    }

    public Integer getApiKeyKeyVersion() {
        return apiKeyKeyVersion;
    }

    public void setApiKeyKeyVersion(Integer apiKeyKeyVersion) {
        this.apiKeyKeyVersion = apiKeyKeyVersion;
    }

    public byte[] getApiSecretCiphertext() {
        return apiSecretCiphertext;
    }

    public void setApiSecretCiphertext(byte[] apiSecretCiphertext) {
        this.apiSecretCiphertext = apiSecretCiphertext;
    }

    public byte[] getApiSecretNonce() {
        return apiSecretNonce;
    }

    public void setApiSecretNonce(byte[] apiSecretNonce) {
        this.apiSecretNonce = apiSecretNonce;
    }

    public Integer getApiSecretKeyVersion() {
        return apiSecretKeyVersion;
    }

    public void setApiSecretKeyVersion(Integer apiSecretKeyVersion) {
        this.apiSecretKeyVersion = apiSecretKeyVersion;
    }

    public byte[] getPassphraseCiphertext() {
        return passphraseCiphertext;
    }

    public void setPassphraseCiphertext(byte[] passphraseCiphertext) {
        this.passphraseCiphertext = passphraseCiphertext;
    }

    public byte[] getPassphraseEncryptionNonce() {
        return passphraseEncryptionNonce;
    }

    public void setPassphraseEncryptionNonce(byte[] passphraseEncryptionNonce) {
        this.passphraseEncryptionNonce = passphraseEncryptionNonce;
    }

    public Integer getPassphraseKeyVersion() {
        return passphraseKeyVersion;
    }

    public void setPassphraseKeyVersion(Integer passphraseKeyVersion) {
        this.passphraseKeyVersion = passphraseKeyVersion;
    }

    public boolean isPaperTrading() {
        return paperTrading;
    }

    public void setPaperTrading(boolean paperTrading) {
        this.paperTrading = paperTrading;
    }

    /**
     * 是否 testnet/沙盒环境:OKX demo key 时 true(走 setSandboxMode + x-simulated-trading header),
     * 生产 key 时 false。跟 paperTrading 独立(paperTrading=自建模拟盘不调交易所;testnet=交易所沙盒环境)。
     * CcxtAuthExchangeFactory + OkxRestClient 读本字段决定 sandbox。
     */
    public boolean isTestnet() {
        return testnet;
    }

    public void setTestnet(boolean testnet) {
        this.testnet = testnet;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
