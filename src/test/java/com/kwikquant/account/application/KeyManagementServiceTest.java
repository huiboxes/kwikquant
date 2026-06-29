package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.ApiKeyEncryptor;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.EncryptionKeyMapper;
import com.kwikquant.account.infrastructure.ExchangeAccountMapper;
import com.kwikquant.shared.types.Exchange;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 master key 用 rootKey 加密后存 DB（DB 单独泄露不暴露 master key）+ 轮换/重启一致性。
 * ExchangeAccount 不入库（decryptSecret 只读字段），避免 users FK 造数。
 */
@SpringBootTest
@Transactional
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class KeyManagementServiceTest extends AbstractIntegrationTest {

    @Autowired
    EncryptionKeyMapper keyMapper;

    @Autowired
    ExchangeAccountMapper accountMapper;

    @Autowired
    @Qualifier("encryptionKey")
    byte[] rootKey;

    private static final String NEW_KEY_B64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="; // 32 bytes

    private ExchangeAccount accountWithSecret(int keyVersion, byte[] plain, byte[] key) {
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        byte[] cipher = ApiKeyEncryptor.encrypt(plain, key, nonce);
        ExchangeAccount acc = new ExchangeAccount();
        acc.setUserId(1L);
        acc.setExchange(Exchange.BINANCE);
        acc.setLabel("test");
        acc.setApiKey("k");
        acc.setApiSecret(cipher);
        acc.setNonce(nonce);
        acc.setKeyVersion(keyVersion);
        return acc;
    }

    @Test
    void rotateKey_shouldStoreMasterKeyEncryptedInDb() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        int version = svc.rotateKey(NEW_KEY_B64);

        var row = keyMapper.findByVersion(version);
        assertThat(row).isNotNull();
        // 存的不能是明文 base64(newKey)——必须是 rootKey 加密后的 blob
        assertThat(row.encryptedKey()).isNotEqualTo(NEW_KEY_B64);
        // 模拟重启：新实例从 DB 解密加载 active master key，应回到 newKey
        var restarted = new KeyManagementService(keyMapper, accountMapper, rootKey);
        assertThat(restarted.getCurrentKeyVersion()).isEqualTo(version);
        assertThat(restarted.getCurrentKey()).isEqualTo(Base64.getDecoder().decode(NEW_KEY_B64));
    }

    @Test
    void rotateKey_thenDecryptV2Secret_shouldRoundTrip() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        int v2 = svc.rotateKey(NEW_KEY_B64);

        byte[] plain = "my-api-secret".getBytes();
        ExchangeAccount acc = accountWithSecret(v2, plain, svc.getCurrentKey());

        // 重启后用 resolveKey(v2) 解密
        var restarted = new KeyManagementService(keyMapper, accountMapper, rootKey);
        assertThat(restarted.decryptSecret(acc)).isEqualTo(plain);
    }

    @Test
    void resolveKey_forV1_shouldDecryptWithRootKey() {
        // v1 master = rootKey（bootstrap，无 DB 行）；resolveKey(1) 走 v1 特殊分支返回 rootKey
        byte[] plain = "v1-secret".getBytes();
        ExchangeAccount acc = accountWithSecret(1, plain, rootKey);

        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        // 即使 DB 有其他 active key，resolveKey(1) 仍应返回 rootKey 解密 v1 数据
        assertThat(svc.decryptSecret(acc)).isEqualTo(plain);
    }

    @Test
    void construct_onEmptyDb_shouldBootstrapToV1WithRootKey() {
        // @Transactional 回滚了其他测试的 rotateKey 插入，encryption_keys 为空 → v1
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        assertThat(svc.getCurrentKeyVersion()).isEqualTo(1);
        assertThat(svc.getCurrentKey()).isEqualTo(rootKey);
    }

    @Test
    void lazyMigrate_shouldReEncryptSecretWithCurrentKey() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        int v2 = svc.rotateKey(NEW_KEY_B64);

        // 账户用 v1（rootKey）加密
        byte[] plain = "secret-to-migrate".getBytes();
        ExchangeAccount acc = accountWithSecret(1, plain, rootKey);

        svc.lazyMigrate(acc); // accountMapper.update 对未入库行 no-op，但字段已 re-encrypt

        assertThat(acc.getKeyVersion()).isEqualTo(v2);
        byte[] decrypted = ApiKeyEncryptor.decrypt(acc.getApiSecret(), svc.getCurrentKey(), acc.getNonce());
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void lazyMigrate_whenSameVersion_shouldBeNoOp() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        int v2 = svc.rotateKey(NEW_KEY_B64);

        byte[] plain = "same-version".getBytes();
        ExchangeAccount acc = accountWithSecret(v2, plain, svc.getCurrentKey());
        byte[] originalCipher = acc.getApiSecret().clone();

        var result = svc.lazyMigrate(acc);
        // keyVersion == current → 直接返回，不动 cipher
        assertThat(result).isSameAs(acc);
        assertThat(acc.getApiSecret()).isEqualTo(originalCipher);
    }

    @Test
    void lazyMigrate_shouldAlsoReEncryptPassphrase() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        int v2 = svc.rotateKey(NEW_KEY_B64);

        byte[] secretPlain = "secret".getBytes();
        byte[] ppPlain = "passphrase".getBytes();
        byte[] ppNonce = ApiKeyEncryptor.generateNonce();
        byte[] ppCipher = ApiKeyEncryptor.encrypt(ppPlain, rootKey, ppNonce);
        ExchangeAccount acc = accountWithSecret(1, secretPlain, rootKey); // v1
        acc.setPassphrase(ppCipher);
        acc.setPassphraseNonce(ppNonce);

        svc.lazyMigrate(acc);

        assertThat(acc.getKeyVersion()).isEqualTo(v2);
        byte[] decryptedPp =
                ApiKeyEncryptor.decrypt(acc.getPassphrase(), svc.getCurrentKey(), acc.getPassphraseNonce());
        assertThat(decryptedPp).isEqualTo(ppPlain);
    }

    @Test
    void decryptPassphrase_whenNull_shouldReturnNull() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        ExchangeAccount acc = accountWithSecret(1, "x".getBytes(), rootKey);
        acc.setPassphrase(null);
        assertThat(svc.decryptPassphrase(acc)).isNull();
    }

    @Test
    void decryptPassphrase_whenPresent_shouldDecryptWithFallbackNonce() {
        // passphraseNonce 为 null → 回退用主 nonce
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        byte[] plain = "pp".getBytes();
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        byte[] cipher = ApiKeyEncryptor.encrypt(plain, rootKey, nonce);
        ExchangeAccount acc = accountWithSecret(1, "secret".getBytes(), rootKey);
        acc.setPassphrase(cipher);
        acc.setPassphraseNonce(null); // 回退到 acc.getNonce()
        // acc.getNonce() 是 accountWithSecret 设的 secret nonce，不是 pp 的 nonce
        // 要让回退生效，pp 必须用 acc.getNonce() 加密——重做：用 acc.getNonce() 加密 pp
        acc.setPassphrase(ApiKeyEncryptor.encrypt(plain, rootKey, acc.getNonce()));
        assertThat(svc.decryptPassphrase(acc)).isEqualTo(plain);
    }

    @Test
    void decryptPassphrase_whenPassphraseNoncePresent_shouldUseIt() {
        // passphraseNonce 非 null → 用它（不走回退分支）
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        byte[] plain = "pp-with-nonce".getBytes();
        byte[] ppNonce = ApiKeyEncryptor.generateNonce();
        ExchangeAccount acc = accountWithSecret(1, "secret".getBytes(), rootKey);
        acc.setPassphrase(ApiKeyEncryptor.encrypt(plain, rootKey, ppNonce));
        acc.setPassphraseNonce(ppNonce);
        assertThat(svc.decryptPassphrase(acc)).isEqualTo(plain);
    }

    @Test
    void rotateKey_whenWrongLength_shouldThrow() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        // 16 字节，不是 32
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> svc.rotateKey(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void resolveKey_forMissingVersion_shouldThrow() {
        // v1（空 DB），account.keyVersion=99 既非 1 也非 current 也查不到
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        ExchangeAccount acc = accountWithSecret(99, "x".getBytes(), rootKey);
        assertThatThrownBy(() -> svc.decryptSecret(acc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 99");
    }

    @Test
    void lazyMigrate_whenPassphraseNonceNull_shouldFallbackToOldNonceForDecrypt() {
        var svc = new KeyManagementService(keyMapper, accountMapper, rootKey);
        int v2 = svc.rotateKey(NEW_KEY_B64);

        // 旧 passphrase 用主 nonce 加密（passphraseNonce=null），迁移时 decrypt 回退用 oldNonce
        byte[] secretPlain = "s".getBytes();
        byte[] ppPlain = "pp".getBytes();
        ExchangeAccount acc = accountWithSecret(1, secretPlain, rootKey);
        acc.setPassphrase(ApiKeyEncryptor.encrypt(ppPlain, rootKey, acc.getNonce()));
        acc.setPassphraseNonce(null);

        svc.lazyMigrate(acc);

        // 迁移后 passphrase 用新 nonce + current key 加密；decrypt 回原文
        byte[] decryptedPp =
                ApiKeyEncryptor.decrypt(acc.getPassphrase(), svc.getCurrentKey(), acc.getPassphraseNonce());
        assertThat(decryptedPp).isEqualTo(ppPlain);
    }
}
