package com.kwikquant.shared.infra;

import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.MDC;

/**
 * 统一响应 envelope。所有 REST endpoint 返回此结构：{@code {code, message, data, traceId}}。
 *
 * <p>前端判断业务结果看 {@link #code()}（非 HTTP status）——风控拒走 HTTP 200 + code=4105 是反例。
 * {@link #traceId()} 用于排障，提交工单带上。
 */
public record ApiResponse<T>(
        @Schema(description = "业务码，0=成功，其余为错误码（见 ErrorCode.java catalog）", example = "0") int code,
        @Schema(description = "消息，成功为 \"ok\"，失败为错误描述", example = "ok") String message,
        @Schema(description = "业务数据，结构因 endpoint 而异；错误时为 null") T data,
        @Schema(description = "链路追踪 ID，用于排障", example = "a1b2c3d4e5f6") String traceId) {

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
