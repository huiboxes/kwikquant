package com.kwikquant.account.application;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.EncryptionKeyMapper;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.shared.infra.Auditable;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(KeyManagementService.class);

    private final Map<Integer, byte[]> encryptionKeys;
    private final EncryptionKeyMapper keyMapper;
    private final ExchangeAccountMapper accountMapper;
    private final AtomicReference<KeyState> keyState;

    record KeyState(byte[] key, int version) {}

    public KeyManagementService(
            Map<Integer, byte[]> encryptionKeys,
            EncryptionKeyMapper keyMapper,
            ExchangeAccountMapper accountMapper) {
        this.encryptionKeys = encryptionKeys;
        this.keyMapper = keyMapper;
        this.accountMapper = accountMapper;
        var active = keyMapper.findActiveKey();
        int version = active != null ? active.keyVersion() : 1;
        byte[] key = encryptionKeys.get(version);
        if (key == null) {
            throw new IllegalStateException(
                    "Active encryption key version " + version + " not found in configuration. "
                            + "Ensure ENCRYPTION_KEYS contains all versions referenced in the database.");
        }
        this.keyState = new AtomicReference<>(new KeyState(key, version));
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
    public int rotateKey(int newVersion) {
        byte[] newKey = encryptionKeys.get(newVersion);
        if (newKey == null) {
            throw new IllegalArgumentException(
                    "Encryption key v" + newVersion + " not found in configuration. "
                            + "Add it to ENCRYPTION_KEYS first, then call rotateKey.");
        }
        keyMapper.deactivateAll();
        keyMapper.insert(new EncryptionKeyMapper.EncryptionKeyRow(newVersion, true));
        keyState.set(new KeyState(newKey, newVersion));
        return newVersion;
    }

    private byte[] resolveKey(int version) {
        KeyState current = keyState.get();
        if (version == current.version()) {
            return current.key();
        }
        byte[] key = encryptionKeys.get(version);
        if (key == null) {
            throw new IllegalStateException("Encryption key version " + version + " not found in configuration");
        }
        return key;
    }
}
