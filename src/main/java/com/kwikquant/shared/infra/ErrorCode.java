package com.kwikquant.shared.infra;

public final class ErrorCode {
    public static final int SUCCESS = 0;
    public static final int UNAUTHENTICATED = 1001;
    public static final int FORBIDDEN = 1002;
    public static final int RISK_REJECTED = 2001;
    public static final int INSUFFICIENT_MARGIN = 2002;
    public static final int VALIDATION_FAILED = 3001;
    public static final int RESOURCE_NOT_FOUND = 4001;
    public static final int IDEMPOTENCY_CONFLICT = 4002;
    public static final int RESOURCE_STATE_CONFLICT = 4009;
    public static final int RATE_LIMITED = 4029;
    public static final int INTERNAL_ERROR = 5001;
    public static final int SERVICE_OVERLOADED = 5031;
    public static final int EXCHANGE_UNAVAILABLE = 6001;

    private ErrorCode() {}
}
