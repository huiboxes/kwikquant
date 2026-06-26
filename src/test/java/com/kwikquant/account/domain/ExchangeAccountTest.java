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
        a.setApiKey("key123");
        a.setApiSecret(new byte[] {1, 2, 3});
        a.setPassphrase(new byte[] {4, 5});
        a.setNonce(new byte[] {6, 7, 8});
        a.setPassphraseNonce(new byte[] {9, 10, 11});
        a.setKeyVersion(2);
        a.setPaperTrading(false);
        a.setStatus("ACTIVE");
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);

        assertEquals(1L, a.getId());
        assertEquals(42L, a.getUserId());
        assertEquals(Exchange.BINANCE, a.getExchange());
        assertEquals("prod", a.getLabel());
        assertEquals("key123", a.getApiKey());
        assertArrayEquals(new byte[] {1, 2, 3}, a.getApiSecret());
        assertArrayEquals(new byte[] {4, 5}, a.getPassphrase());
        assertArrayEquals(new byte[] {6, 7, 8}, a.getNonce());
        assertArrayEquals(new byte[] {9, 10, 11}, a.getPassphraseNonce());
        assertEquals(2, a.getKeyVersion());
        assertFalse(a.isPaperTrading());
        assertEquals("ACTIVE", a.getStatus());
        assertEquals(now, a.getCreatedAt());
        assertEquals(now, a.getUpdatedAt());
    }
}
