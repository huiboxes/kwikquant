package com.kwikquant.account.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class ApiKeyEncryptorTest {

    private static final byte[] KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(KEY);
    }

    @Test
    void encryptAndDecrypt() {
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        byte[] plaintext = "my-secret-api-key".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = ApiKeyEncryptor.encrypt(plaintext, KEY, nonce);
        assertNotEquals(new String(plaintext), new String(ciphertext));

        byte[] decrypted = ApiKeyEncryptor.decrypt(ciphertext, KEY, nonce);
        assertEquals("my-secret-api-key", new String(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    void differentNonceProducesDifferentCiphertext() {
        byte[] plaintext = "same-secret".getBytes(StandardCharsets.UTF_8);
        byte[] n1 = ApiKeyEncryptor.generateNonce();
        byte[] n2 = ApiKeyEncryptor.generateNonce();

        byte[] c1 = ApiKeyEncryptor.encrypt(plaintext, KEY, n1);
        byte[] c2 = ApiKeyEncryptor.encrypt(plaintext, KEY, n2);
        assertNotEquals(java.util.Arrays.toString(c1), java.util.Arrays.toString(c2));
    }

    @Test
    void wrongKeyFails() {
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        byte[] ciphertext = ApiKeyEncryptor.encrypt("secret".getBytes(), KEY, nonce);

        byte[] wrongKey = new byte[32];
        new SecureRandom().nextBytes(wrongKey);
        assertThrows(IllegalStateException.class, () -> ApiKeyEncryptor.decrypt(ciphertext, wrongKey, nonce));
    }

    @Test
    void wrongNonceFails() {
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        byte[] ciphertext = ApiKeyEncryptor.encrypt("secret".getBytes(), KEY, nonce);

        byte[] wrongNonce = ApiKeyEncryptor.generateNonce();
        assertThrows(IllegalStateException.class, () -> ApiKeyEncryptor.decrypt(ciphertext, KEY, wrongNonce));
    }

    @Test
    void nonceIs12Bytes() {
        byte[] nonce = ApiKeyEncryptor.generateNonce();
        assertEquals(12, nonce.length);
    }
}
