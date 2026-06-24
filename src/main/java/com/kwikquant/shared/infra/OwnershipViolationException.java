package com.kwikquant.shared.infra;

public class OwnershipViolationException extends RuntimeException {
    public OwnershipViolationException(String resourceType) {
        super("ownership violation on " + resourceType);
    }
}
