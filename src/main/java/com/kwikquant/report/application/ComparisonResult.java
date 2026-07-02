package com.kwikquant.report.application;

import com.kwikquant.report.domain.BacktestReport;
import java.util.List;
import java.util.Map;

public record ComparisonResult(List<BacktestReport> reports, Map<String, List<Long>> ranking) {}
