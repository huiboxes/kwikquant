package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.report.application.TradeHistoryService.TradeHistoryItem;
import com.kwikquant.report.domain.ReportExportFailedException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TradeHistoryExportServiceTest {

    private ObjectMapper objectMapper;
    private TradeHistoryExportService exportService;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        exportService = new TradeHistoryExportService(objectMapper);
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
    void exportCsv_correctFormatWithBom() {
        byte[] result = exportService.exportCsv(List.of(sampleItem()));

        // UTF-8 BOM: EF BB BF
        assertThat(result[0] & 0xFF).isEqualTo(0xEF);
        assertThat(result[1] & 0xFF).isEqualTo(0xBB);
        assertThat(result[2] & 0xFF).isEqualTo(0xBF);

        String csv = new String(result, StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        // BOM + header line + 1 data line
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("orderId,accountId,symbol,side");
        assertThat(lines[1]).startsWith("1,10,BTC/USDT,BUY");
    }

    @Test
    void exportCsv_emptyItems_headerOnly() {
        byte[] result = exportService.exportCsv(List.of());

        String csv = new String(result, StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(1);
        assertThat(lines[0]).contains("orderId");
    }

    @Test
    void exportCsv_injectionPrevention() {
        TradeHistoryItem injectionItem = new TradeHistoryItem(
                2L,
                10L,
                "=HYPERLINK(\"http://evil.com\")",
                "BUY",
                "LIMIT",
                new BigDecimal("1"),
                new BigDecimal("1"),
                new BigDecimal("100"),
                BigDecimal.ZERO,
                new BigDecimal("100"),
                "FILLED",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z"));

        byte[] result = exportService.exportCsv(List.of(injectionItem));

        String csv = new String(result, StandardCharsets.UTF_8);
        String dataLine = csv.split("\n")[1];
        // Leading '=' should be stripped by sanitizeCsvField
        assertThat(dataLine).doesNotContain("=HYPERLINK");
    }

    @Test
    void exportJson_validArray() {
        byte[] result = exportService.exportJson(List.of(sampleItem()));

        String json = new String(result, StandardCharsets.UTF_8);
        assertThat(json).startsWith("[");
        assertThat(json).contains("\"orderId\"");
        assertThat(json).contains("\"accountId\"");
        assertThat(json).contains("\"symbol\"");
        assertThat(json).contains("BTC/USDT");
    }

    @Test
    void exportJson_emptyItems_emptyArray() {
        byte[] result = exportService.exportJson(List.of());

        String json = new String(result, StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void exportJson_serializationFailure_throwsExportFailed() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsBytes(any()))
                .thenThrow(new tools.jackson.core.exc.StreamWriteException(null, "boom"));
        TradeHistoryExportService brokenService = new TradeHistoryExportService(brokenMapper);

        assertThatThrownBy(() -> brokenService.exportJson(List.of(sampleItem())))
                .isInstanceOf(ReportExportFailedException.class)
                .hasMessageContaining("JSON export failed");
    }
}
