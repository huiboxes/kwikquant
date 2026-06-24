package com.kwikquant.shared.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OwnershipCheckTest {

    @Test
    void requireOwnedReturnsEntityWhenOwnerMatches() {
        String entity = "test-entity";
        String result = OwnershipCheck.requireOwned(entity, 1L, 1L, "test");
        assertSame(entity, result);
    }

    @Test
    void requireOwnedThrowsNotFoundWhenEntityIsNull() {
        assertThrows(ResourceNotFoundException.class, () -> OwnershipCheck.requireOwned(null, 1L, 1L, "test"));
    }

    @Test
    void requireOwnedThrowsViolationWhenOwnerDiffers() {
        assertThrows(OwnershipViolationException.class, () -> OwnershipCheck.requireOwned("entity", 1L, 2L, "test"));
    }
}
