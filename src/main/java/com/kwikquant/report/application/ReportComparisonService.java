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

    /** 对比最少报告数，也被 {@code CompareRequest} 的 {@code @Size} 校验引用。 */
    public static final int MIN_REPORTS = 2;

    /** 对比最多报告数，也被 {@code CompareRequest} 的 {@code @Size} 校验引用。 */
    public static final int MAX_REPORTS = 20;

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
                "totalReturn",
                rank(reports, Comparator.comparing(BacktestReport::getTotalReturn, nullsLastDescending())));
        ranking.put(
                "sharpeRatio",
                rank(reports, Comparator.comparing(BacktestReport::getSharpeRatio, nullsLastDescending())));
        ranking.put(
                "maxDrawdown",
                rank(reports, Comparator.comparing(BacktestReport::getMaxDrawdown, nullsLastAscending())));
        ranking.put("winRate", rank(reports, Comparator.comparing(BacktestReport::getWinRate, nullsLastDescending())));
        ranking.put(
                "profitFactor",
                rank(reports, Comparator.comparing(BacktestReport::getProfitFactor, nullsLastDescending())));
        ranking.put(
                "totalTrades",
                rank(
                        reports,
                        Comparator.comparingInt(BacktestReport::getTotalTrades).reversed()));
        ranking.put(
                "avgTradeDuration",
                rank(reports, Comparator.comparingLong(BacktestReport::getAvgTradeDurationSeconds)));

        return new ComparisonResult(reports, ranking);
    }

    /** 值越大越好，降序排列；null（未计算出该指标）始终排最后，不受方向影响。 */
    private static Comparator<BigDecimal> nullsLastDescending() {
        return Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder().reversed());
    }

    /** 值越小越好，升序排列；null 始终排最后。 */
    private static Comparator<BigDecimal> nullsLastAscending() {
        return Comparator.nullsLast(Comparator.naturalOrder());
    }

    private static List<Long> rank(List<BacktestReport> reports, Comparator<BacktestReport> cmp) {
        List<BacktestReport> sorted = new ArrayList<>(reports);
        sorted.sort(cmp);
        return sorted.stream().map(BacktestReport::getId).toList();
    }
}
