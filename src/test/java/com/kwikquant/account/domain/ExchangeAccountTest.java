package com.kwikquant.account.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.kwikquant.shared.types.Exchange;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExchangeAccountTest {

    @Test
    void settersAndGetters() {
        ExchangeAccount a = new ExchangeAccount();
        a.setId(1L);
        a.setUserId(42L);
        a.setExchange(Exchange.BINANCE);
        a.setLabel("prod");
        a.setApiKeyCiphertext(new byte[] {1});
        a.setApiKeyNonce(new byte[] {2});
        a.setApiKeyKeyVersion(1);
        a.setApiSecretCiphertext(new byte[] {3});
        a.setApiSecretNonce(new byte[] {4});
        a.setApiSecretKeyVersion(2);
        a.setPassphraseCiphertext(new byte[] {5});
        a.setPassphraseEncryptionNonce(new byte[] {6});
        a.setPassphraseKeyVersion(3);
        a.setPaperTrading(false);
        a.setStatus("ACTIVE");
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);

        assertEquals(1L, a.getId());
        assertEquals(42L, a.getUserId());
        assertEquals(Exchange.BINANCE, a.getExchange());
        assertEquals("prod", a.getLabel());
        assertArrayEquals(new byte[] {1}, a.getApiKeyCiphertext());
        assertArrayEquals(new byte[] {2}, a.getApiKeyNonce());
        assertEquals(1, a.getApiKeyKeyVersion());
        assertArrayEquals(new byte[] {3}, a.getApiSecretCiphertext());
        assertArrayEquals(new byte[] {4}, a.getApiSecretNonce());
        assertEquals(2, a.getApiSecretKeyVersion());
        assertArrayEquals(new byte[] {5}, a.getPassphraseCiphertext());
        assertArrayEquals(new byte[] {6}, a.getPassphraseEncryptionNonce());
        assertEquals(3, a.getPassphraseKeyVersion());
        assertFalse(a.isPaperTrading());
        assertEquals("ACTIVE", a.getStatus());
        assertEquals(now, a.getCreatedAt());
        assertEquals(now, a.getUpdatedAt());
    }
}
