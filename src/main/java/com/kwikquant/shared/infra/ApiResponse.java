package com.kwikquant.shared.infra;

import org.slf4j.MDC;

public record ApiResponse<T>(int code, String message, T data, String traceId) {

    /** 自动从 MDC 获取 traceId（推荐用法）。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS, "ok", data, MDC.get(MdcKeys.TRACE_ID));
    }

    /** @deprecated 使用 {@link #ok(Object)} 代替，避免手动传 traceId 遗漏。 */
    @Deprecated
    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(ErrorCode.SUCCESS, "ok", data, traceId);
    }

    public static <T> ApiResponse<T> error(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, traceId);
    }
}
