package com.kwikquant.account.domain;

import com.kwikquant.shared.types.Exchange;
import java.time.Instant;

public final class ExchangeAccount {

    private Long id;
    private long userId;
    private Exchange exchange;
    private String label;
    private String apiKey;
    private byte[] apiSecret;
    private byte[] passphrase;
    private byte[] nonce;
    private byte[] passphraseNonce;
    private int keyVersion;
    private boolean paperTrading;
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

    public byte[] getPassphrase() {
        return passphrase;
    }

    public void setPassphrase(byte[] passphrase) {
        this.passphrase = passphrase;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    public byte[] getPassphraseNonce() {
        return passphraseNonce;
    }

    public void setPassphraseNonce(byte[] passphraseNonce) {
        this.passphraseNonce = passphraseNonce;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public boolean isPaperTrading() {
        return paperTrading;
    }

    public void setPaperTrading(boolean paperTrading) {
        this.paperTrading = paperTrading;
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
