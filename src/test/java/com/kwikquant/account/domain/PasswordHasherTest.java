package com.kwikquant.account.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashAndVerify() {
        String hash = PasswordHasher.hash("myPassword123");
        assertTrue(hash.startsWith("$argon2id$"));
        assertTrue(PasswordHasher.verify("myPassword123", hash));
    }

    @Test
    void wrongPasswordFails() {
        String hash = PasswordHasher.hash("correct");
        assertFalse(PasswordHasher.verify("wrong", hash));
    }

    @Test
    void differentHashesForSamePassword() {
        String h1 = PasswordHasher.hash("same");
        String h2 = PasswordHasher.hash("same");
        assertNotEquals(h1, h2);
        assertTrue(PasswordHasher.verify("same", h1));
        assertTrue(PasswordHasher.verify("same", h2));
    }

    @Test
    void invalidFormatThrows() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.verify("pw", "not-a-hash"));
    }
}
