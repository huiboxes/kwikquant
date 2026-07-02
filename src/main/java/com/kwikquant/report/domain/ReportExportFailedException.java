package com.kwikquant.report.domain;

public class ReportExportFailedException extends RuntimeException {
    public ReportExportFailedException(String message) {
        super(message);
    }

    public ReportExportFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
