package com.kwikquant.account.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void constructorSetsFields() {
        User user = new User("alice", "alice@test.com", "$argon2id$hash");
        assertEquals("alice", user.getUsername());
        assertEquals("alice@test.com", user.getEmail());
        assertEquals("$argon2id$hash", user.getPasswordHash());
        assertTrue(user.isEnabled());
    }

    @Test
    void settersWork() {
        User user = new User();
        user.setId(42L);
        user.setUsername("bob");
        user.setEmail("bob@test.com");
        user.setPasswordHash("hash");
        user.setEnabled(false);

        assertEquals(42L, user.getId());
        assertEquals("bob", user.getUsername());
        assertEquals("bob@test.com", user.getEmail());
        assertEquals("hash", user.getPasswordHash());
        assertFalse(user.isEnabled());
    }

    @Test
    void timestampSetters() {
        User user = new User();
        java.time.Instant now = java.time.Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        assertEquals(now, user.getCreatedAt());
        assertEquals(now, user.getUpdatedAt());
    }
}
