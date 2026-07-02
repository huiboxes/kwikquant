package com.kwikquant.report.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

public class ReportNotFoundException extends ResourceNotFoundException {
    public ReportNotFoundException(String message) {
        super(message);
    }
}
