package com.kwikquant.report.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.report.domain.ReportExportFailedException;
import com.kwikquant.report.domain.ReportInvalidPayloadException;
import com.kwikquant.report.domain.ReportNotFoundException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * ReportExceptionHandler 单测:3 个 @ExceptionHandler 映射到正确 ErrorCode + HttpStatus。
 * 直接调 handler 方法(覆盖方法体);@ResponseStatus 注解的 HTTP 状态由 Spring MVC 集测验证,这里
 * 只验证业务码 + 消息(防 e.getMessage() 泄漏 S3 凭证/路径)。
 */
class ReportExceptionHandlerTest {

    private final ReportExceptionHandler handler = new ReportExceptionHandler();

    @Test
    void handleNotFound_returnsReportNotFoundCode() {
        ApiResponse<Void> resp = handler.handleNotFound(new ReportNotFoundException("report 99 missing"));

        assertThat(resp.code()).isEqualTo(ErrorCode.REPORT_NOT_FOUND);
        // ReportNotFoundException extends ResourceNotFoundException,message 带 " not found" 后缀
        assertThat(resp.message()).contains("report 99 missing");
    }

    @Test
    void handleInvalidPayload_returnsInvalidPayloadCode() {
        ApiResponse<Void> resp = handler.handleInvalidPayload(new ReportInvalidPayloadException("bad range"));

        assertThat(resp.code()).isEqualTo(ErrorCode.REPORT_INVALID_PAYLOAD);
        assertThat(resp.message()).isEqualTo("bad range");
    }

    @Test
    void handleExportFailed_returnsExportFailedCodeWithSanitizedMessage() {
        ApiResponse<Void> resp =
                handler.handleExportFailed(new ReportExportFailedException("s3 bucket denied: AKIA..."));

        assertThat(resp.code()).isEqualTo(ErrorCode.REPORT_EXPORT_FAILED);
        // 消息固定 "export failed",不透传 e.getMessage()(防 S3 凭证/路径泄漏)
        assertThat(resp.message()).isEqualTo("export failed");
    }
}
