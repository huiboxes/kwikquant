package com.kwikquant.report.interfaces;

import com.kwikquant.report.application.TradeHistoryExportService;
import com.kwikquant.report.application.TradeHistoryService;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.PageDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
class TradeHistoryController {

    private final TradeHistoryService tradeHistoryService;
    private final TradeHistoryExportService exportService;

    TradeHistoryController(TradeHistoryService tradeHistoryService, TradeHistoryExportService exportService) {
        this.tradeHistoryService = tradeHistoryService;
        this.exportService = exportService;
    }

    @GetMapping
    ApiResponse<PageDto<TradeHistoryDto>> query(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        long userId = SecurityUtils.currentUserId();
        PageDto<TradeHistoryService.TradeHistoryItem> result =
                tradeHistoryService.query(userId, accountId, symbol, startTime, endTime, page, pageSize);

        List<TradeHistoryDto> dtos =
                result.content().stream().map(TradeHistoryController::toDto).toList();

        return ApiResponse.ok(PageDto.of(dtos, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/stats")
    ApiResponse<TradeHistoryStatsDto> stats(
            @RequestParam(required = false) Long accountId, @RequestParam(required = false) Instant since) {
        long userId = SecurityUtils.currentUserId();
        TradeHistoryService.TradeHistoryStats stats = tradeHistoryService.stats(userId, accountId, since);
        return ApiResponse.ok(new TradeHistoryStatsDto(stats.totalVolume(), stats.totalFees(), stats.realizedPnl()));
    }

    @GetMapping("/export")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime,
            @RequestParam(defaultValue = "csv") String format) {
        long userId = SecurityUtils.currentUserId();
        PageDto<TradeHistoryService.TradeHistoryItem> result =
                tradeHistoryService.query(userId, accountId, symbol, startTime, endTime, 1, 10000);

        List<TradeHistoryService.TradeHistoryItem> items = result.content();

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
