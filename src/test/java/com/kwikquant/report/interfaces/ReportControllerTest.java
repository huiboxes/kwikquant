package com.kwikquant.report.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.report.application.ComparisonResult;
import com.kwikquant.report.application.ReportComparisonService;
import com.kwikquant.report.application.ReportService;
import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.TradeRecord;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ReportControllerTest {

    private ReportService reportService;
    private ReportComparisonService comparisonService;
    private ReportController controller;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        comparisonService = mock(ReportComparisonService.class);
        controller = new ReportController(reportService, comparisonService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private BacktestReport sampleReport() {
        BacktestReport report = new BacktestReport();
        report.setId(100L);
        report.setUserId(42L);
        report.setName("Test Strategy");
        report.setSymbol("BTC/USDT");
        report.setTimeframe("1h");
        report.setPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        report.setPeriodEnd(Instant.parse("2026-06-01T00:00:00Z"));
        report.setTotalReturn(new BigDecimal("0.15"));
        report.setSharpeRatio(new BigDecimal("1.5"));
        report.setMaxDrawdown(new BigDecimal("0.08"));
        report.setWinRate(new BigDecimal("0.6"));
        report.setProfitFactor(new BigDecimal("2.1"));
        report.setTotalTrades(50);
        report.setAvgTradeDurationSeconds(3600L);
        report.setSource("PLATFORM");
        report.setCreatedAt(Instant.parse("2026-06-15T10:00:00Z"));
        return report;
    }

    private BacktestSubmitRequest sampleSubmitRequest() {
        return new BacktestSubmitRequest(
                "Test Strategy",
                Map.of("ma_period", 20),
                "BTC/USDT",
                "1h",
                new BacktestSubmitRequest.PeriodRange(
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-06-01T00:00:00Z")),
                List.of(new BacktestSubmitRequest.TradeEntry(
                        Instant.parse("2026-01-15T00:00:00Z"),
                        "buy",
                        new BigDecimal("50000"),
                        new BigDecimal("0.1"),
                        new BigDecimal("0.5"))),
                null);
    }

    @Test
    void submit_happyPath_returnsDtoWithCorrectFields() {
        BacktestReport report = sampleReport();
        // Controller calls service.submit with individual args
        when(reportService.submit(eq(42L), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(report);

        ApiResponse<BacktestReportDto> response = controller.submit(sampleSubmitRequest());

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().id()).isEqualTo(100L);
        assertThat(response.data().name()).isEqualTo("Test Strategy");
        assertThat(response.data().symbol()).isEqualTo("BTC/USDT");
        assertThat(response.data().source()).isEqualTo("PLATFORM");
        assertThat(response.data().totalTrades()).isEqualTo(50);
        // BacktestReportDto has NO avgTradeDurationSeconds field
    }

    @Test
    void submit_passesIndividualArgsToService() {
        when(reportService.submit(eq(42L), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(sampleReport());

        controller.submit(sampleSubmitRequest());

        verify(reportService)
                .submit(
                        eq(42L),
                        eq("Test Strategy"),
                        any(),
                        eq("BTC/USDT"),
                        eq("1h"),
                        eq(Instant.parse("2026-01-01T00:00:00Z")),
                        eq(Instant.parse("2026-06-01T00:00:00Z")),
                        any(),
                        isNull());
    }

    @Test
    void list_delegatesWithPagination() {
        BacktestReport report = sampleReport();
        PageDto<BacktestReport> page = PageDto.of(List.of(report), 1, 20, 1L);
        // list(page, pageSize, symbol) — symbol is 3rd param
        when(reportService.listByUser(eq(42L), eq("BTC/USDT"), any(PageQuery.class)))
                .thenReturn(page);

        ApiResponse<PageDto<BacktestReportDto>> response = controller.list(1, 20, "BTC/USDT");

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().content()).hasSize(1);
        assertThat(response.data().content().getFirst().id()).isEqualTo(100L);
        assertThat(response.data().total()).isEqualTo(1L);
        verify(reportService).listByUser(eq(42L), eq("BTC/USDT"), any(PageQuery.class));
    }

    @Test
    void detail_returnsFullReport() {
        BacktestReport report = sampleReport();
        report.setEquityCurve(null);

        TradeRecord trade = new TradeRecord();
        trade.setTime(Instant.parse("2026-01-15T00:00:00Z"));
        trade.setSide("buy");
        trade.setPrice(new BigDecimal("50000"));
        trade.setAmount(new BigDecimal("0.1"));
        trade.setFee(new BigDecimal("0.5"));

        when(reportService.getById(100L, 42L)).thenReturn(report);
        when(reportService.getTradeRecords(100L, 42L)).thenReturn(List.of(trade));
        when(reportService.parseEquityCurve(isNull())).thenReturn(List.of());

        // method is "detail" not "getDetail"
        ApiResponse<BacktestReportDetailDto> response = controller.detail(100L);

        assertThat(response.code()).isEqualTo(0);
        BacktestReportDetailDto detail = response.data();
        assertThat(detail.id()).isEqualTo(100L);
        assertThat(detail.metrics().totalReturn()).isEqualByComparingTo("0.15");
        assertThat(detail.trades()).hasSize(1);
        assertThat(detail.trades().getFirst().side()).isEqualTo("buy");
        assertThat(detail.equityCurve()).isEmpty();
    }

    @Test
    void compare_happyPath_returnsRanking() {
        BacktestReport r1 = sampleReport();
        BacktestReport r2 = sampleReport();
        r2.setId(101L);

        Map<String, List<Long>> ranking = Map.of("totalReturn", List.of(100L, 101L));
        ComparisonResult result = new ComparisonResult(List.of(r1, r2), ranking);
        // compare(reportIds, userId) — note arg order
        when(comparisonService.compare(eq(List.of(100L, 101L)), eq(42L))).thenReturn(result);

        CompareRequest request = new CompareRequest(List.of(100L, 101L));
        ApiResponse<ComparisonResultDto> response = controller.compare(request);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().reports()).hasSize(2);
        assertThat(response.data().ranking()).containsKey("totalReturn");
        assertThat(response.data().ranking().get("totalReturn")).containsExactly(100L, 101L);
    }

    @Test
    void importResult_setsSourceImport() {
        BacktestReport report = sampleReport();
        report.setSource("IMPORT");
        when(reportService.importResult(eq(42L), anyString(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(report);

        ApiResponse<BacktestReportDto> response = controller.importResult(sampleSubmitRequest());

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().source()).isEqualTo("IMPORT");
    }
}
