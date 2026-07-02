package com.kwikquant.report.interfaces;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompareRequest(
        @NotNull @Size(min = 2, max = 20, message = "reportIds must contain 2-20 entries") List<Long> reportIds) {}
