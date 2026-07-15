package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.TradeHistoryExportService;
import com.kwikquant.report.application.TradeHistoryService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trade-history")
@Validated
@Tag(name = "交易历史")
class TradeHistoryController {

    private final TradeHistoryService tradeHistoryService;
    private final TradeHistoryExportService exportService;

    TradeHistoryController(TradeHistoryService tradeHistoryService, TradeHistoryExportService exportService) {
        this.tradeHistoryService = tradeHistoryService;
        this.exportService = exportService;
    }

    @GetMapping
    @Operation(summary = "分页查询交易历史", description = "聚合多账户订单 + 成交，按订单维度返回。需 JWT 鉴权。accountId 为空表示查当前用户全部账户。")
    ApiResponse<PageDto<TradeHistoryDto>> query(
            @Parameter(description = "账户 ID，为空则查全部账户", example = "42") @RequestParam(required = false) Long accountId,
            @Parameter(description = "按 canonical symbol 过滤，如 BTC/USDT", example = "BTC/USDT")
                    @RequestParam(required = false)
                    String symbol,
            @Parameter(description = "起始时间 ISO-8601，为空则不限", example = "2026-07-01T00:00:00Z")
                    @RequestParam(required = false)
                    Instant startTime,
            @Parameter(description = "结束时间 ISO-8601，为空则不限", example = "2026-07-04T00:00:00Z")
                    @RequestParam(required = false)
                    Instant endTime,
            @Parameter(description = "页码，1-based，默认 1", example = "1") @RequestParam(required = false) Integer page,
            @Parameter(description = "每页条数，1-100，默认 20", example = "20") @RequestParam(required = false)
                    Integer pageSize) {
        long userId = SecurityUtils.currentUserId();
        PageQuery pq = PageQuery.ofStandard(page, pageSize);
        PageDto<TradeHistoryService.TradeHistoryItem> result =
                tradeHistoryService.query(userId, accountId, symbol, startTime, endTime, pq);

        List<TradeHistoryDto> dtos =
                result.content().stream().map(TradeHistoryController::toDto).toList();

        return ApiResponse.ok(PageDto.of(dtos, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/stats")
    @Operation(summary = "交易统计", description = "按账户/时间范围聚合成交额、累计手续费、已实现盈亏。需 JWT 鉴权。accountId 为空表示全部账户。")
    ApiResponse<TradeHistoryStatsDto> stats(
            @Parameter(description = "账户 ID，为空则全部账户", example = "42") @RequestParam(required = false) Long accountId,
            @Parameter(description = "统计起始时间 ISO-8601，为空则不限", example = "2026-07-01T00:00:00Z")
                    @RequestParam(required = false)
                    Instant since) {
        long userId = SecurityUtils.currentUserId();
        TradeHistoryService.TradeHistoryStats stats = tradeHistoryService.stats(userId, accountId, since);
        return ApiResponse.ok(new TradeHistoryStatsDto(
                stats.totalVolume(), stats.totalFees(), stats.realizedPnl(), stats.tradeCount(), stats.winRate()));
    }

    @GetMapping("/export")
    @Operation(summary = "导出交易历史", description = "按条件导出 CSV/JSON 文件。需 JWT 鉴权。导出失败返回 9004。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "导出失败（9004 REPORT_EXPORT_FAILED：序列化或 IO 异常）")
    ResponseEntity<byte[]> export(
            @Parameter(description = "账户 ID，为空则全部账户", example = "42") @RequestParam(required = false) Long accountId,
            @Parameter(description = "按 canonical symbol 过滤", example = "BTC/USDT") @RequestParam(required = false)
                    String symbol,
            @Parameter(description = "起始时间 ISO-8601", example = "2026-07-01T00:00:00Z") @RequestParam(required = false)
                    Instant startTime,
            @Parameter(description = "结束时间 ISO-8601", example = "2026-07-04T00:00:00Z") @RequestParam(required = false)
                    Instant endTime,
            @Parameter(description = "导出格式（枚举: csv | json），默认 csv", example = "csv") @RequestParam(defaultValue = "csv")
                    String format) {
        long userId = SecurityUtils.currentUserId();
        List<TradeHistoryService.TradeHistoryItem> items =
                tradeHistoryService.queryAll(userId, accountId, symbol, startTime, endTime);

        if ("json".equalsIgnoreCase(format)) {
            byte[] data = exportService.exportJson(items);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=trade-history.json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(data);
        }

        byte[] data = exportService.exportCsv(items);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=trade-history.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(data);
    }

    private static TradeHistoryDto toDto(TradeHistoryService.TradeHistoryItem item) {
        return new TradeHistoryDto(
                item.orderId(),
                item.accountId(),
                item.symbol(),
                item.side(),
                item.orderType(),
                item.amount(),
                item.filledQty(),
                item.filledAvgPrice(),
                item.totalFee(),
                item.totalVolume(),
                item.status(),
                item.createdAt(),
                item.updatedAt());
    }
}
