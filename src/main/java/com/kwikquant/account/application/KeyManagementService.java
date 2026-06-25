package com.kwikquant.account.application;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.EncryptionKeyMapper;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.shared.infra.Auditable;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(KeyManagementService.class);

    private final EncryptionKeyMapper keyMapper;
    private final ExchangeAccountMapper accountMapper;
    private final AtomicReference<KeyState> keyState;

    record KeyState(byte[] key, int version) {}

    public KeyManagementService(
            EncryptionKeyMapper keyMapper,
            ExchangeAccountMapper accountMapper,
            @Qualifier("encryptionKey") byte[] currentKey) {
        this.keyMapper = keyMapper;
        this.accountMapper = accountMapper;
        var active = keyMapper.findActiveKey();
        int version = active != null ? active.keyVersion() : 1;
        this.keyState = new AtomicReference<>(new KeyState(currentKey, version));
    }

    public int getCurrentKeyVersion() {
        return keyState.get().version();
    }

    public byte[] getCurrentKey() {
        byte[] key = keyState.get().key();
        return Arrays.copyOf(key, key.length);
    }

    public byte[] decryptSecret(ExchangeAccount account) {
        byte[] key = resolveKey(account.getKeyVersion());
        return ApiKeyEncryptor.decrypt(account.getApiSecret(), key, account.getNonce());
    }

    public byte[] decryptPassphrase(ExchangeAccount account) {
        if (account.getPassphrase() == null) {
            return null;
        }
        byte[] key = resolveKey(account.getKeyVersion());
        byte[] nonce = account.getPassphraseNonce() != null ? account.getPassphraseNonce() : account.getNonce();
        return ApiKeyEncryptor.decrypt(account.getPassphrase(), key, nonce);
    }

    @Transactional
    public ExchangeAccount lazyMigrate(ExchangeAccount account) {
        KeyState current = keyState.get();
        if (account.getKeyVersion() == current.version()) {
            return account;
        }
        log.info(
                "Lazy-migrating exchange_account {} from key_version {} to {}",
                account.getId(),
                account.getKeyVersion(),
                current.version());

        byte[] oldKey = resolveKey(account.getKeyVersion());
        byte[] oldNonce = account.getNonce();
        byte[] plainSecret = ApiKeyEncryptor.decrypt(account.getApiSecret(), oldKey, oldNonce);

        byte[] newNonce = ApiKeyEncryptor.generateNonce();
        byte[] newCipher = ApiKeyEncryptor.encrypt(plainSecret, current.key(), newNonce);
        account.setApiSecret(newCipher);
        account.setNonce(newNonce);

        if (account.getPassphrase() != null) {
            byte[] ppNonce = account.getPassphraseNonce() != null ? account.getPassphraseNonce() : oldNonce;
            byte[] plainPp = ApiKeyEncryptor.decrypt(account.getPassphrase(), oldKey, ppNonce);
            byte[] newPpNonce = ApiKeyEncryptor.generateNonce();
            account.setPassphrase(ApiKeyEncryptor.encrypt(plainPp, current.key(), newPpNonce));
            account.setPassphraseNonce(newPpNonce);
        }

        account.setKeyVersion(current.version());
        accountMapper.update(account);
        return account;
    }

    @Transactional
    @Auditable(action = "KEY_ROTATION", targetType = "encryption_key")
    public int rotateKey(String newKeyBase64) {
        byte[] newKey = Base64.getDecoder().decode(newKeyBase64);
        if (newKey.length != 32) {
            throw new IllegalArgumentException("New key must be 32 bytes");
        }
        int nextVersion = keyState.get().version() + 1;
        keyMapper.deactivateAll();
        keyMapper.insert(new EncryptionKeyMapper.EncryptionKeyRow(
                nextVersion, Base64.getEncoder().encodeToString(newKey), true));
        keyState.set(new KeyState(newKey, nextVersion));
        return nextVersion;
    }

    private byte[] resolveKey(int version) {
        KeyState current = keyState.get();
        if (version == current.version()) {
            return current.key();
        }
        var row = keyMapper.findByVersion(version);
        if (row == null) {
            throw new IllegalStateException("Encryption key version " + version + " not found");
        }
        return Base64.getDecoder().decode(row.encryptedKey());
    }
}
