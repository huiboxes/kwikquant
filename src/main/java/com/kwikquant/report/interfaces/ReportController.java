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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "报告与组合")
class ReportController {

    private final ReportService reportService;
    private final ReportComparisonService comparisonService;

    ReportController(ReportService reportService, ReportComparisonService comparisonService) {
        this.reportService = reportService;
        this.comparisonService = comparisonService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "提交回测报告",
            description = "用户提交回测结果（含交易明细 + 权益曲线）生成报告。需 JWT 鉴权。" + "服务端校验交易数量/权益点数上限、价格/数量正值、时间区间合法性，" + "非法时返回 9002。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "报告载荷非法（9002 REPORT_INVALID_PAYLOAD：交易为空/超限、价格数量非正、区间非法等）")
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
    @Operation(summary = "分页查询回测报告", description = "按当前用户鉴权过滤，可选 symbol 过滤。结果按创建时间倒序。需 JWT 鉴权。")
    ApiResponse<PageDto<BacktestReportDto>> list(
            @Parameter(description = "页码，1-based，默认 1", example = "1") @RequestParam(defaultValue = "1") @Min(1)
                    int page,
            @Parameter(description = "每页条数，1-100，默认 20", example = "20")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int pageSize,
            @Parameter(description = "按 canonical symbol 过滤，如 BTC/USDT；为空则不过滤", example = "BTC/USDT")
                    @RequestParam(required = false)
                    String symbol) {
        long userId = SecurityUtils.currentUserId();
        PageDto<BacktestReport> result = reportService.listByUser(userId, symbol, page, pageSize);
        List<BacktestReportDto> dtos =
                result.content().stream().map(ReportController::toDto).toList();
        return ApiResponse.ok(PageDto.of(dtos, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询报告详情", description = "含指标、交易明细、权益曲线。鉴权校验报告归属。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "报告不存在或不属于当前用户（9001 REPORT_NOT_FOUND）")
    ApiResponse<BacktestReportDetailDto> detail(
            @Parameter(description = "报告 ID", example = "42") @PathVariable long id) {
        long userId = SecurityUtils.currentUserId();
        BacktestReport report = reportService.getById(id, userId);
        List<TradeRecord> trades = reportService.getTradeRecords(id, userId);
        List<EquityPoint> equityCurve = reportService.parseEquityCurve(report.getEquityCurve());

        BacktestReportDetailDto detail = toDetailDto(report, trades, equityCurve);
        return ApiResponse.ok(detail);
    }

    @PostMapping("/compare")
    @Operation(summary = "多策略报告对比", description = "传入 ≥2 个 reportId，返回报告列表 + 按指标的排名。鉴权校验每个报告归属。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "对比报告数不足/超限（9002 REPORT_INVALID_PAYLOAD：<2 或 >20 个 reportId）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "可访问的报告不足以对比（9001 REPORT_NOT_FOUND：reportId 不存在或不属于当前用户）")
    ApiResponse<ComparisonResultDto> compare(@Valid @RequestBody CompareRequest request) {
        long userId = SecurityUtils.currentUserId();
        ComparisonResult result = comparisonService.compare(request.reportIds(), userId);

        List<BacktestReportDto> dtos =
                result.reports().stream().map(ReportController::toDto).toList();
        return ApiResponse.ok(new ComparisonResultDto(dtos, result.ranking()));
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "导入回测结果",
            description = "与 /reports 类似，但 source 标记为 IMPORT，用于外部回测结果入库。需 JWT 鉴权。" + "服务端校验同 submit，非法时返回 9002。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "报告载荷非法（9002 REPORT_INVALID_PAYLOAD：交易为空/超限、价格数量非正、区间非法等）")
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
