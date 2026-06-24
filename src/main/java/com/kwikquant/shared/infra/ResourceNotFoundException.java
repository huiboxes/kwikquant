package com.kwikquant.shared.infra;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resourceType) {
        super(resourceType + " not found");
    }

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(resourceType + " not found: " + resourceId);
    }
}
