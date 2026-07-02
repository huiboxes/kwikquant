package com.kwikquant.report.application;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.ReportInvalidPayloadException;
import com.kwikquant.report.domain.ReportNotFoundException;
import com.kwikquant.report.infrastructure.BacktestReportMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReportComparisonService {

    private static final int MIN_REPORTS = 2;
    private static final int MAX_REPORTS = 20;

    private final BacktestReportMapper reportMapper;

    public ReportComparisonService(BacktestReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    public ComparisonResult compare(List<Long> reportIds, long userId) {
        if (reportIds == null || reportIds.size() < MIN_REPORTS) {
            throw new ReportInvalidPayloadException("at least " + MIN_REPORTS + " reports required for comparison");
        }
        if (reportIds.size() > MAX_REPORTS) {
            throw new ReportInvalidPayloadException("comparison supports at most " + MAX_REPORTS + " reports");
        }

        List<BacktestReport> reports = reportMapper.findByIds(reportIds, userId);
        if (reports.size() < MIN_REPORTS) {
            throw new ReportNotFoundException("insufficient accessible reports for comparison");
        }

        Map<String, List<Long>> ranking = new LinkedHashMap<>();
        ranking.put(
                "totalReturn", rank(reports, Comparator.comparing(BacktestReport::getTotalReturn, nullsLast()), true));
        ranking.put(
                "sharpeRatio", rank(reports, Comparator.comparing(BacktestReport::getSharpeRatio, nullsLast()), true));
        ranking.put(
                "maxDrawdown", rank(reports, Comparator.comparing(BacktestReport::getMaxDrawdown, nullsLast()), false));
        ranking.put("winRate", rank(reports, Comparator.comparing(BacktestReport::getWinRate, nullsLast()), true));
        ranking.put(
                "profitFactor",
                rank(reports, Comparator.comparing(BacktestReport::getProfitFactor, nullsLast()), true));
        ranking.put("totalTrades", rank(reports, Comparator.comparingInt(BacktestReport::getTotalTrades), true));
        ranking.put(
                "avgTradeDuration",
                rank(reports, Comparator.comparingLong(BacktestReport::getAvgTradeDurationSeconds), false));

        return new ComparisonResult(reports, ranking);
    }

    private static Comparator<BigDecimal> nullsLast() {
        return Comparator.nullsLast(Comparator.naturalOrder());
    }

    private static <T> List<Long> rank(
            List<BacktestReport> reports, Comparator<BacktestReport> cmp, boolean descending) {
        List<BacktestReport> sorted = new ArrayList<>(reports);
        sorted.sort(descending ? cmp.reversed() : cmp);
        return sorted.stream().map(BacktestReport::getId).toList();
    }
}
