package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

public record ComparisonResultDto(
        @Schema(description = "对比报告列表") List<BacktestReportDto> reports,
        @Schema(description = "按指标的排名：key=指标名，value=按该指标排序的 reportId 列表", example = "{\"totalReturn\":[43,42]}")
                Map<String, List<Long>> ranking) {}
