package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.EncryptionKeyMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class KeyManagementServiceTest extends AbstractIntegrationTest {

    private static final String NEW_KEY_B64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Autowired
    EncryptionKeyMapper keyMapper;

    @Autowired
    @Qualifier("encryptionKey")
    byte[] rootKey;

    @Test
    void rotateKeyStoresEncryptedMasterKeyAndSurvivesRestart() {
        var service = service();
        int version = service.rotateKey(NEW_KEY_B64);

        assertThat(keyMapper.findByVersion(version).encryptedKey()).isNotEqualTo(NEW_KEY_B64);
        var restarted = service();
        assertThat(restarted.getCurrentKeyVersion()).isEqualTo(version);
        assertThat(restarted.getCurrentKey()).isEqualTo(Base64.getDecoder().decode(NEW_KEY_B64));
    }

    @Test
    void decryptsAllIndependentCredentialEnvelopes() {
        var service = service();
        ExchangeAccount account = encryptedAccount(service, "key", "secret", "passphrase");

        assertThat(service.decryptApiKey(account)).isEqualTo(bytes("key"));
        assertThat(service.decryptSecret(account)).isEqualTo(bytes("secret"));
        assertThat(service.decryptPassphrase(account)).isEqualTo(bytes("passphrase"));
    }

    @Test
    void wrongKeyFailsClosed() {
        var service = service();
        ExchangeAccount account = encryptedAccount(service, "key", "secret", null);
        account.setApiSecretKeyVersion(99);

        assertThatThrownBy(() -> service.decryptSecret(account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version 99");
    }

    @Test
    void tamperedCiphertextFailsGcmAuthentication() {
        var service = service();
        ExchangeAccount account = encryptedAccount(service, "key", "secret", null);
        account.getApiSecretCiphertext()[0] ^= 1;

        assertThatThrownBy(() -> service.decryptSecret(account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    private KeyManagementService service() {
        return new KeyManagementService(keyMapper, rootKey);
    }

    private static ExchangeAccount encryptedAccount(
            KeyManagementService service, String apiKey, String secret, String passphrase) {
        ExchangeAccount account = new ExchangeAccount();
        setApiKey(account, service.encryptCredential(bytes(apiKey)));
        setSecret(account, service.encryptCredential(bytes(secret)));
        if (passphrase != null) {
            setPassphrase(account, service.encryptCredential(bytes(passphrase)));
        }
        return account;
    }

    private static void setApiKey(ExchangeAccount account, KeyManagementService.EncryptedValue value) {
        account.setApiKeyCiphertext(value.ciphertext());
        account.setApiKeyNonce(value.nonce());
        account.setApiKeyKeyVersion(value.keyVersion());
    }

    private static void setSecret(ExchangeAccount account, KeyManagementService.EncryptedValue value) {
        account.setApiSecretCiphertext(value.ciphertext());
        account.setApiSecretNonce(value.nonce());
        account.setApiSecretKeyVersion(value.keyVersion());
    }

    private static void setPassphrase(ExchangeAccount account, KeyManagementService.EncryptedValue value) {
        account.setPassphraseCiphertext(value.ciphertext());
        account.setPassphraseEncryptionNonce(value.nonce());
        account.setPassphraseKeyVersion(value.keyVersion());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
