package com.kwikquant.account.application;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.account.infrastructure.EncryptionKeyMapper;
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
    private final byte[] rootKey; // env ENCRYPTION_KEY，用于加密/解密 DB 中的 master key
    private final AtomicReference<KeyState> keyState;

    record KeyState(byte[] key, int version) {}

    public record EncryptedValue(byte[] ciphertext, byte[] nonce, int keyVersion) {
        @Override
        public String toString() {
            return "EncryptedValue[redacted, keyVersion=" + keyVersion + "]";
        }
    }

    public KeyManagementService(EncryptionKeyMapper keyMapper, @Qualifier("encryptionKey") byte[] currentKey) {
        this.keyMapper = keyMapper;
        this.rootKey = currentKey;
        // 启动时从 DB 加载当前 master key（用 rootKey 解密）；DB 空则 v1 master = rootKey（bootstrap）
        var active = keyMapper.findActiveKey();
        if (active != null) {
            byte[] masterKey = decryptMasterKey(active.encryptedKey());
            this.keyState = new AtomicReference<>(new KeyState(masterKey, active.keyVersion()));
        } else {
            this.keyState = new AtomicReference<>(new KeyState(currentKey, 1));
        }
    }

    public int getCurrentKeyVersion() {
        return keyState.get().version();
    }

    public byte[] getCurrentKey() {
        byte[] key = keyState.get().key();
        return Arrays.copyOf(key, key.length);
    }

    public EncryptedValue encryptCredential(byte[] plaintext) {
        KeyState current = keyState.get();
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        return new EncryptedValue(ApiKeyEncryptor.encrypt(plaintext, current.key(), nonce), nonce, current.version());
    }

    public byte[] decryptApiKey(ExchangeAccount account) {
        return decryptCredential(
                account.getApiKeyCiphertext(), account.getApiKeyNonce(), account.getApiKeyKeyVersion(), "apiKey");
    }

    public byte[] decryptSecret(ExchangeAccount account) {
        return decryptCredential(
                account.getApiSecretCiphertext(),
                account.getApiSecretNonce(),
                account.getApiSecretKeyVersion(),
                "apiSecret");
    }

    /**
     * 解密 LLM API key 的完整 secret。
     *
     * <p>与 {@link #decryptSecret(ExchangeAccount)} 对称：用 {@code key.keyVersion} 解析对应版本的 master key，
     * 再用 {@code key.nonce} 解密 {@code key.apiSecret}。{@code resolveKey} 仍为 private，不暴露
     * {@code getKeyByVersion}（避免 master key 句柄外泄）。
     */
    public byte[] decryptSecret(LlmApiKey key) {
        byte[] masterKey = resolveKey(key.getKeyVersion());
        return ApiKeyEncryptor.decrypt(key.getApiSecret(), masterKey, key.getNonce());
    }

    public byte[] decryptPassphrase(ExchangeAccount account) {
        if (account.getPassphraseCiphertext() == null) {
            return null;
        }
        return decryptCredential(
                account.getPassphraseCiphertext(),
                account.getPassphraseEncryptionNonce(),
                account.getPassphraseKeyVersion(),
                "passphrase");
    }

    byte[] decryptLegacy(byte[] ciphertext, byte[] nonce, int keyVersion) {
        return decryptCredential(ciphertext, nonce, keyVersion, "legacy credential");
    }

    private byte[] decryptCredential(byte[] ciphertext, byte[] nonce, Integer keyVersion, String field) {
        if (ciphertext == null || nonce == null || keyVersion == null) {
            throw new IllegalStateException("Incomplete encrypted " + field + " envelope");
        }
        byte[] key = resolveKey(keyVersion);
        try {
            return ApiKeyEncryptor.decrypt(ciphertext, key, nonce);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Transactional
    @Auditable(action = "KEY_ROTATION", targetType = "encryption_key")
    public synchronized int rotateKey(String newKeyBase64) {
        byte[] newKey = Base64.getDecoder().decode(newKeyBase64);
        if (newKey.length != ApiKeyEncryptor.AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("New key must be " + ApiKeyEncryptor.AES_256_KEY_BYTES + " bytes");
        }
        int nextVersion = keyState.get().version() + 1;
        // master key 用 rootKey 加密后存 DB（defense-in-depth：DB 单独泄露不暴露 master key）
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        byte[] cipher = ApiKeyEncryptor.encrypt(newKey, rootKey, nonce);
        String stored = encodeMasterKey(nonce, cipher);
        keyMapper.deactivateAll();
        keyMapper.insert(new EncryptionKeyMapper.EncryptionKeyRow(nextVersion, stored, true));
        keyState.set(new KeyState(newKey, nextVersion));
        return nextVersion;
    }

    private byte[] resolveKey(int version) {
        KeyState current = keyState.get();
        if (version == current.version()) {
            byte[] k = current.key();
            return Arrays.copyOf(k, k.length);
        }
        // v1 master key = rootKey（bootstrap，无 DB 行）
        if (version == 1) {
            return Arrays.copyOf(rootKey, rootKey.length);
        }
        var row = keyMapper.findByVersion(version);
        if (row == null) {
            throw new IllegalStateException("Encryption key version " + version + " not found");
        }
        return decryptMasterKey(row.encryptedKey());
    }

    /** 解密 DB 中的 master key：存储格式 base64(nonce({@value ApiKeyEncryptor#NONCE_LENGTH}) || ciphertext)。 */
    private byte[] decryptMasterKey(String stored) {
        byte[] blob = Base64.getDecoder().decode(stored);
        byte[] nonce = Arrays.copyOf(blob, ApiKeyEncryptor.NONCE_LENGTH);
        byte[] cipher = Arrays.copyOfRange(blob, ApiKeyEncryptor.NONCE_LENGTH, blob.length);
        return ApiKeyEncryptor.decrypt(cipher, rootKey, nonce);
    }

    private static String encodeMasterKey(byte[] nonce, byte[] cipher) {
        byte[] blob = new byte[nonce.length + cipher.length];
        System.arraycopy(nonce, 0, blob, 0, nonce.length);
        System.arraycopy(cipher, 0, blob, nonce.length, cipher.length);
        return Base64.getEncoder().encodeToString(blob);
    }
}
