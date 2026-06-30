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

    // Trading 模块 41xx 段
    public static final int ORDER_NOT_FOUND = 4100;
    public static final int ORDER_ILLEGAL_STATE_TRANSITION = 4101;
    public static final int ORDER_INSUFFICIENT_BALANCE = 4102;
    public static final int ORDER_INVALID_PARAMS = 4103;
    public static final int ORDER_EXCHANGE_REJECTED = 4104;
    public static final int ORDER_RISK_REJECTED = 4105;
    public static final int ORDER_MATCHING_FAILED = 4106;
    public static final int ORDER_CONCURRENCY_CONFLICT = 4107;
    public static final int ORDER_EXCHANGE_API_ERROR = 4108;

    private ErrorCode() {}
}
