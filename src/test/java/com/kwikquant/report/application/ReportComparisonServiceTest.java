package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.ReportInvalidPayloadException;
import com.kwikquant.report.domain.ReportNotFoundException;
import com.kwikquant.report.infrastructure.BacktestReportMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportComparisonServiceTest {

    private BacktestReportMapper reportMapper;
    private ReportComparisonService service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(BacktestReportMapper.class);
        service = new ReportComparisonService(reportMapper);
    }

    @Test
    void compare_happyPath_rankingCorrect() {
        BacktestReport r1 = buildReport(1L, "0.10");
        BacktestReport r2 = buildReport(2L, "0.30");
        BacktestReport r3 = buildReport(3L, "0.20");

        when(reportMapper.findByIds(any(), eq(42L))).thenReturn(List.of(r1, r2, r3));

        ComparisonResult result = service.compare(List.of(1L, 2L, 3L), 42L);

        assertThat(result.reports()).hasSize(3);
        assertThat(result.ranking()).containsKey("totalReturn");

        // totalReturn ranked DESC: r2 (0.30) > r3 (0.20) > r1 (0.10)
        List<Long> totalReturnRanking = result.ranking().get("totalReturn");
        assertThat(totalReturnRanking).containsExactly(2L, 3L, 1L);
    }

    @Test
    void compare_twoReports_returnsAllRankingKeys() {
        BacktestReport r1 = buildReport(1L, "0.10");
        BacktestReport r2 = buildReport(2L, "0.20");

        when(reportMapper.findByIds(any(), eq(42L))).thenReturn(List.of(r1, r2));

        ComparisonResult result = service.compare(List.of(1L, 2L), 42L);

        assertThat(result.ranking())
                .containsKeys(
                        "totalReturn",
                        "sharpeRatio",
                        "maxDrawdown",
                        "winRate",
                        "profitFactor",
                        "totalTrades",
                        "avgTradeDuration");
    }

    @Test
    void compare_lessThan2_throwsInvalidPayload() {
        assertThatThrownBy(() -> service.compare(List.of(1L), 42L))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("2");
    }

    @Test
    void compare_nullIds_throwsInvalidPayload() {
        assertThatThrownBy(() -> service.compare(null, 42L))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("2");
    }

    @Test
    void compare_moreThan20_throwsInvalidPayload() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            ids.add(i);
        }

        assertThatThrownBy(() -> service.compare(ids, 42L))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("20");
    }

    @Test
    void compare_insufficientAccessibleReports_throwsReportNotFound() {
        // request 3 but only 1 found (need at least 2)
        when(reportMapper.findByIds(any(), eq(42L))).thenReturn(List.of(buildReport(1L, "0.10")));

        assertThatThrownBy(() -> service.compare(List.of(1L, 2L, 3L), 42L))
                .isInstanceOf(ReportNotFoundException.class)
                .hasMessageContaining("insufficient");
    }

    // ---------- helper ----------

    private static BacktestReport buildReport(long id, String totalReturn) {
        BacktestReport r = new BacktestReport();
        r.setId(id);
        r.setUserId(42L);
        r.setTotalReturn(new BigDecimal(totalReturn));
        r.setSharpeRatio(new BigDecimal("1.0"));
        r.setMaxDrawdown(new BigDecimal("0.05"));
        r.setWinRate(new BigDecimal("0.5"));
        r.setProfitFactor(new BigDecimal("1.5"));
        r.setTotalTrades(10);
        r.setAvgTradeDurationSeconds(3600L);
        return r;
    }
}
