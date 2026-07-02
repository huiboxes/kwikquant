package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.ComparisonResult;
import com.kwikquant.report.application.ReportComparisonService;
import com.kwikquant.report.application.ReportService;
import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.EquityPoint;
import com.kwikquant.report.domain.TradeRecord;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.PageDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Validated
class ReportController {

    private final ReportService reportService;
    private final ReportComparisonService comparisonService;

    ReportController(ReportService reportService, ReportComparisonService comparisonService) {
        this.reportService = reportService;
        this.comparisonService = comparisonService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<BacktestReportDto> submit(@Valid @RequestBody BacktestSubmitRequest request) {
        long userId = SecurityUtils.currentUserId();
        List<TradeRecord> trades = toTradeRecords(request.trades());
        List<EquityPoint> equity = toEquityPoints(request.equityCurve());

        BacktestReport report = reportService.submit(
                userId,
                request.name(),
                request.params(),
                request.symbol(),
                request.timeframe(),
                request.period().start(),
                request.period().end(),
                trades,
                equity);

        return ApiResponse.ok(toDto(report));
    }

    @GetMapping
    ApiResponse<PageDto<BacktestReportDto>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String symbol) {
        long userId = SecurityUtils.currentUserId();
        PageDto<BacktestReport> result = reportService.listByUser(userId, symbol, page, pageSize);
        List<BacktestReportDto> dtos =
                result.content().stream().map(ReportController::toDto).toList();
        return ApiResponse.ok(PageDto.of(dtos, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/{id}")
    ApiResponse<BacktestReportDetailDto> detail(@PathVariable long id) {
        long userId = SecurityUtils.currentUserId();
        BacktestReport report = reportService.getById(id, userId);
        List<TradeRecord> trades = reportService.getTradeRecords(id, userId);
        List<EquityPoint> equityCurve = reportService.parseEquityCurve(report.getEquityCurve());

        BacktestReportDetailDto detail = toDetailDto(report, trades, equityCurve);
        return ApiResponse.ok(detail);
    }

    @PostMapping("/compare")
    ApiResponse<ComparisonResultDto> compare(@Valid @RequestBody CompareRequest request) {
        long userId = SecurityUtils.currentUserId();
        ComparisonResult result = comparisonService.compare(request.reportIds(), userId);

        List<BacktestReportDto> dtos =
                result.reports().stream().map(ReportController::toDto).toList();
        return ApiResponse.ok(new ComparisonResultDto(dtos, result.ranking()));
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<BacktestReportDto> importResult(@Valid @RequestBody BacktestSubmitRequest request) {
        long userId = SecurityUtils.currentUserId();
        List<TradeRecord> trades = toTradeRecords(request.trades());
        List<EquityPoint> equity = toEquityPoints(request.equityCurve());

        BacktestReport report = reportService.importResult(
                userId,
                request.name(),
                request.params(),
                request.symbol(),
                request.timeframe(),
                request.period().start(),
                request.period().end(),
                trades,
                equity);

        return ApiResponse.ok(toDto(report));
    }

    // --- mapping helpers ---

    private static BacktestReportDto toDto(BacktestReport r) {
        return new BacktestReportDto(
                r.getId(),
                r.getName(),
                r.getSymbol(),
                r.getTimeframe(),
                r.getPeriodStart(),
                r.getPeriodEnd(),
                r.getTotalReturn(),
                r.getSharpeRatio(),
                r.getMaxDrawdown(),
                r.getWinRate(),
                r.getProfitFactor(),
                r.getTotalTrades(),
                r.getSource(),
                r.getCreatedAt());
    }

    private static BacktestReportDetailDto toDetailDto(
            BacktestReport r, List<TradeRecord> trades, List<EquityPoint> equityCurve) {
        var metrics = new BacktestReportDetailDto.MetricsDto(
                r.getTotalReturn(),
                r.getSharpeRatio(),
                r.getMaxDrawdown(),
                r.getWinRate(),
                r.getProfitFactor(),
                r.getTotalTrades(),
                r.getAvgTradeDurationSeconds());

        List<BacktestReportDetailDto.TradeRecordDto> tradeDtos = trades.stream()
                .map(t -> new BacktestReportDetailDto.TradeRecordDto(
                        t.getId(), t.getTime(), t.getSide(), t.getPrice(), t.getAmount(), t.getFee()))
                .toList();

        List<BacktestReportDetailDto.EquityPointDto> eqDtos = equityCurve.stream()
                .map(e -> new BacktestReportDetailDto.EquityPointDto(e.time(), e.equity()))
                .toList();

        return new BacktestReportDetailDto(
                r.getId(),
                r.getName(),
                r.getSymbol(),
                r.getTimeframe(),
                r.getPeriodStart(),
                r.getPeriodEnd(),
                r.getParams(),
                metrics,
                tradeDtos,
                eqDtos,
                r.getSource(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private static List<TradeRecord> toTradeRecords(List<BacktestSubmitRequest.TradeEntry> entries) {
        if (entries == null) return List.of();
        return entries.stream()
                .map(e -> {
                    TradeRecord tr = new TradeRecord();
                    tr.setTime(e.time());
                    tr.setSide(e.side());
                    tr.setPrice(e.price());
                    tr.setAmount(e.amount());
                    tr.setFee(e.fee() != null ? e.fee() : BigDecimal.ZERO);
                    return tr;
                })
                .toList();
    }

    private static List<EquityPoint> toEquityPoints(List<BacktestSubmitRequest.EquityPointEntry> entries) {
        if (entries == null) return null;
        return entries.stream().map(e -> new EquityPoint(e.time(), e.equity())).toList();
    }
}
