package com.kwikquant.shared.infra;

public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(ErrorCode.SUCCESS, "ok", data, traceId);
    }

    public static <T> ApiResponse<T> error(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }
}
