package com.kwikquant.report.interfaces;

import com.kwikquant.report.domain.ReportExportFailedException;
import com.kwikquant.report.domain.ReportInvalidPayloadException;
import com.kwikquant.report.domain.ReportNotFoundException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(0)
class ReportExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportExceptionHandler.class);

    @ExceptionHandler(ReportNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleNotFound(ReportNotFoundException e) {
        return ApiResponse.error(ErrorCode.REPORT_NOT_FOUND, e.getMessage(), traceId());
    }

    @ExceptionHandler(ReportInvalidPayloadException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidPayload(ReportInvalidPayloadException e) {
        return ApiResponse.error(ErrorCode.REPORT_INVALID_PAYLOAD, e.getMessage(), traceId());
    }

    @ExceptionHandler(ReportExportFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleExportFailed(ReportExportFailedException e) {
        log.error("[report] export failed", e);
        return ApiResponse.error(ErrorCode.REPORT_EXPORT_FAILED, "export failed", traceId());
    }

    private static String traceId() {
        return MDC.get("traceId");
    }
}
