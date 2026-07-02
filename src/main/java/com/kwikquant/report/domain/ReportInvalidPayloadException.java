package com.kwikquant.report.domain;

public class ReportInvalidPayloadException extends IllegalArgumentException {
    public ReportInvalidPayloadException(String message) {
        super(message);
    }
}
