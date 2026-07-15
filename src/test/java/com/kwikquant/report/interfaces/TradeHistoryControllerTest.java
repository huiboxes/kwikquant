package com.kwikquant.report.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.report.application.TradeHistoryExportService;
import com.kwikquant.report.application.TradeHistoryService;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryItem;
import com.kwikquant.report.application.TradeHistoryService.TradeHistoryStats;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.types.PageDto;
import com.kwikquant.shared.types.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TradeHistoryControllerTest {

    private TradeHistoryService tradeHistoryService;
    private TradeHistoryExportService exportService;
    private TradeHistoryController controller;

    @BeforeEach
    void setUp() {
        tradeHistoryService = mock(TradeHistoryService.class);
        exportService = mock(TradeHistoryExportService.class);
        controller = new TradeHistoryController(tradeHistoryService, exportService);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("42", "x"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private TradeHistoryItem sampleItem() {
        return new TradeHistoryItem(
                1L,
                10L,
                "BTC/USDT",
                "BUY",
                "LIMIT",
                new BigDecimal("0.1"),
                new BigDecimal("0.1"),
                new BigDecimal("50000"),
                new BigDecimal("0.5"),
                new BigDecimal("5000"),
                "FILLED",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z"));
    }

    @Test
    void query_happyPath_returnsPaged() {
        TradeHistoryItem item = sampleItem();
        PageDto<TradeHistoryItem> page = PageDto.of(List.of(item), 1, 20, 1L);
        when(tradeHistoryService.query(eq(42L), eq(10L), isNull(), isNull(), isNull(), any(PageQuery.class)))
                .thenReturn(page);

        ApiResponse<PageDto<TradeHistoryDto>> response = controller.query(10L, null, null, null, 1, 20);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().content()).hasSize(1);

        TradeHistoryDto dto = response.data().content().getFirst();
        assertThat(dto.orderId()).isEqualTo(1L);
        assertThat(dto.accountId()).isEqualTo(10L);
        assertThat(dto.symbol()).isEqualTo("BTC/USDT");
        assertThat(dto.side()).isEqualTo("BUY");
        assertThat(dto.orderType()).isEqualTo("LIMIT");
        assertThat(dto.totalVolume()).isEqualByComparingTo("5000");
        assertThat(dto.updatedAt()).isNotNull();
        assertThat(response.data().total()).isEqualTo(1L);
    }

    @Test
    void query_noAccountId_passesNullToService() {
        PageDto<TradeHistoryItem> page = PageDto.of(List.of(), 1, 20, 0L);
        when(tradeHistoryService.query(eq(42L), isNull(), isNull(), isNull(), isNull(), any(PageQuery.class)))
                .thenReturn(page);

        ApiResponse<PageDto<TradeHistoryDto>> response = controller.query(null, null, null, null, 1, 20);

        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().content()).isEmpty();
        verify(tradeHistoryService)
                .query(
                        eq(42L),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        argThat(pq -> pq.page() == 1 && pq.pageSize() == 20));
    }

    @Test
    void stats_happyPath_returnsStats() {
        TradeHistoryStats stats =
                new TradeHistoryStats(new BigDecimal("500000"), new BigDecimal("250"), new BigDecimal("5000"));
        // controller calls service.stats(userId=42, accountId, since)
        when(tradeHistoryService.stats(eq(42L), eq(10L), isNull())).thenReturn(stats);

        ApiResponse<TradeHistoryStatsDto> response = controller.stats(10L, null);

        assertThat(response.code()).isEqualTo(0);
        TradeHistoryStatsDto dto = response.data();
        assertThat(dto.totalVolume()).isEqualByComparingTo("500000");
        assertThat(dto.totalFees()).isEqualByComparingTo("250");
        assertThat(dto.realizedPnl()).isEqualByComparingTo("5000");
    }

    @Test
    void stats_noAccountId_passesNull() {
        TradeHistoryStats stats = new TradeHistoryStats(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(tradeHistoryService.stats(eq(42L), isNull(), isNull())).thenReturn(stats);

        ApiResponse<TradeHistoryStatsDto> response = controller.stats(null, null);

        assertThat(response.code()).isEqualTo(0);
        verify(tradeHistoryService).stats(42L, null, null);
    }

    @Test
    void export_csv_returnsContentDisposition() {
        TradeHistoryItem item = sampleItem();
        when(tradeHistoryService.queryAll(eq(42L), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(item));

        byte[] csvBytes = "orderId,accountId,symbol\n1,10,BTC/USDT\n".getBytes();
        when(exportService.exportCsv(any())).thenReturn(csvBytes);

        ResponseEntity<byte[]> response = controller.export(null, null, null, null, "csv");

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("attachment")
                .contains("trade-history.csv");
        assertThat(response.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(response.getBody()).isEqualTo(csvBytes);
    }

    @Test
    void export_json_returnsJsonContentType() {
        TradeHistoryItem item = sampleItem();
        when(tradeHistoryService.queryAll(eq(42L), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(item));

        byte[] jsonBytes = "[{\"orderId\":1}]".getBytes();
        when(exportService.exportJson(any())).thenReturn(jsonBytes);

        ResponseEntity<byte[]> response = controller.export(null, null, null, null, "json");

        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("attachment")
                .contains("trade-history.json");
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        assertThat(response.getBody()).isEqualTo(jsonBytes);
    }

    @Test
    void export_withAccountId_passesToQueryAll() {
        when(tradeHistoryService.queryAll(eq(42L), eq(10L), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        byte[] csvBytes = "header\n".getBytes();
        when(exportService.exportCsv(any())).thenReturn(csvBytes);

        controller.export(10L, null, null, null, "csv");

        verify(tradeHistoryService).queryAll(42L, 10L, null, null, null);
    }
}
