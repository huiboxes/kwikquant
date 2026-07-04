package com.kwikquant.shared.infra;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException e) {
        log.debug("access denied", e);
        return ApiResponse.error(ErrorCode.FORBIDDEN, "access denied", traceId());
    }

    @ExceptionHandler(OwnershipViolationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleOwnershipViolation(OwnershipViolationException e) {
        log.debug("ownership violation", e);
        return ApiResponse.error(ErrorCode.FORBIDDEN, "access denied", traceId());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleResourceNotFound(ResourceNotFoundException e) {
        return ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND, "resource not found", traceId());
    }

    @ExceptionHandler(DuplicateMcpTokenException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleDuplicateMcpToken(DuplicateMcpTokenException e) {
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, e.getMessage(), traceId());
    }

    // MCP 工具异常映射（R3-01）：@McpTool 方法异常不经 @RestControllerAdvice（Spring AI MCP server
    // 自包装为 MCP error response {isError:true, content:message}），MCP 协议无数字 errorCode 字段，
    // 10xxx 码靠 message 文本传 Agent，数字码为 REST/日志/审计用。此二 handler 为 REST 路径防御
    // （未来 REST controller 若抛此异常即生效），MCP 路径由工具层自处理（如 catch RiskRejectedException
    // 转 OrderView{RISK_REJECTED}）。MCP E2E 冒烟验证见 Wave 验证阶段。
    @ExceptionHandler(McpToolParamInvalidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMcpToolParamInvalid(McpToolParamInvalidException e) {
        return ApiResponse.error(ErrorCode.MCP_TOOL_PARAM_INVALID, e.getMessage(), traceId());
    }

    @ExceptionHandler(McpEmergencyConfirmRequiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMcpEmergencyConfirm(McpEmergencyConfirmRequiredException e) {
        return ApiResponse.error(ErrorCode.MCP_EMERGENCY_CONFIRM_REQUIRED, e.getMessage(), traceId());
    }

    @ExceptionHandler(ResourceStateConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleResourceStateConflict(ResourceStateConflictException e) {
        log.debug("resource state conflict: {}", e.resourceType(), e);
        return ApiResponse.error(ErrorCode.RESOURCE_STATE_CONFLICT, "resource state conflict", traceId());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFound(NoResourceFoundException e) {
        return ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND, "resource not found", traceId());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (msg.isEmpty()) {
            msg = "validation failed";
        }
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, msg, traceId());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArg(IllegalArgumentException e) {
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, e.getMessage(), traceId());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        if (msg.isEmpty()) {
            msg = "validation failed";
        }
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, msg, traceId());
    }

    @ExceptionHandler(ExchangeException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Void> handleExchange(ExchangeException e) {
        log.error("exchange error (retryable={})", e.isRetryable(), e);
        return ApiResponse.error(ErrorCode.EXCHANGE_UNAVAILABLE, "exchange unavailable", traceId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR, "internal error", traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
