package com.kwikquant.report.interfaces;

import java.util.List;
import java.util.Map;

public record ComparisonResultDto(List<BacktestReportDto> reports, Map<String, List<Long>> ranking) {}
