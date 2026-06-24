package com.kwikquant.shared.infra;

public final class OwnershipCheck {

    private OwnershipCheck() {}

    public static <T> T requireOwned(T entity, long entityOwnerId, long currentUserId, String resourceType) {
        if (entity == null) {
            throw new ResourceNotFoundException(resourceType);
        }
        if (entityOwnerId != currentUserId) {
            throw new OwnershipViolationException(resourceType);
        }
        return entity;
    }
}
