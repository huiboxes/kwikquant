package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.ReportComparisonService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompareRequest(
        @Schema(description = "对比报告 ID 列表，2-20 个", example = "[42, 43]")
                @NotNull
                @Size(
                        min = ReportComparisonService.MIN_REPORTS,
                        max = ReportComparisonService.MAX_REPORTS,
                        message = "reportIds must contain "
                                + ReportComparisonService.MIN_REPORTS
                                + "-"
                                + ReportComparisonService.MAX_REPORTS
                                + " entries")
                List<Long> reportIds) {}
