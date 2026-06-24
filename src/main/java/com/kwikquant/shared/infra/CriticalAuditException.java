package com.kwikquant.shared.infra;

public final class CriticalAuditException extends RuntimeException {
    public CriticalAuditException(String action, Throwable cause) {
        super("critical audit save failed for action=" + action, cause);
    }
}
