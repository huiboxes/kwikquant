package com.kwikquant.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.EquityPoint;
import com.kwikquant.report.domain.ReportInvalidPayloadException;
import com.kwikquant.report.domain.ReportNotFoundException;
import com.kwikquant.report.domain.TradeRecord;
import com.kwikquant.report.infrastructure.BacktestReportMapper;
import com.kwikquant.report.infrastructure.TradeRecordMapper;
import com.kwikquant.shared.types.PageDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ReportServiceTest {

    private BacktestReportMapper reportMapper;
    private TradeRecordMapper tradeRecordMapper;
    private ReportService service;

    private static final long USER_ID = 42L;
    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2025-06-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        reportMapper = mock(BacktestReportMapper.class);
        tradeRecordMapper = mock(TradeRecordMapper.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        service = new ReportService(reportMapper, tradeRecordMapper, objectMapper);
        ReflectionTestUtils.setField(service, "riskFreeRate", new BigDecimal("0.02"));
    }

    private TradeRecord validTrade(String side, BigDecimal price, BigDecimal amount) {
        TradeRecord tr = new TradeRecord();
        tr.setTime(Instant.parse("2025-03-01T12:00:00Z"));
        tr.setSide(side);
        tr.setPrice(price);
        tr.setAmount(amount);
        tr.setFee(BigDecimal.ZERO);
        return tr;
    }

    private List<TradeRecord> validTrades() {
        return List.of(
                validTrade("BUY", new BigDecimal("50000"), BigDecimal.ONE),
                validTrade("SELL", new BigDecimal("51000"), BigDecimal.ONE));
    }

    // --- submit ---

    @Test
    void submit_validInput_createsReportWithPlatformSource() {
        doAnswer(inv -> {
                    BacktestReport r = inv.getArgument(0);
                    r.setId(100L);
                    return null;
                })
                .when(reportMapper)
                .insert(any(BacktestReport.class));

        BacktestReport result = service.submit(
                USER_ID, "my-test", Map.of("sma", 20), "BTC/USDT", "1h", START, END, validTrades(), null);

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getSource()).isEqualTo("PLATFORM");
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getSymbol()).isEqualTo("BTC/USDT");
        verify(reportMapper).insert(any());
        verify(tradeRecordMapper).batchInsert(any());
        verify(reportMapper).updateMetrics(any());
    }

    @Test
    void submitBacktestResult_validSection8_parsesAndCreatesReport() {
        doAnswer(inv -> {
                    BacktestReport r = inv.getArgument(0);
                    r.setId(200L);
                    return null;
                })
                .when(reportMapper)
                .insert(any(BacktestReport.class));

        String section8 = "{\"name\":\"ma-test\",\"params\":{\"fast\":10},\"symbol\":\"BTC/USDT\",\"timeframe\":\"1h\","
                + "\"period\":{\"start\":\"2025-01-01\",\"end\":\"2025-06-01\"},"
                + "\"trades\":[{\"time\":\"2025-03-01T12:00:00Z\",\"side\":\"buy\",\"price\":\"42150\",\"amount\":\"0.1\",\"fee\":\"4.215\"}],"
                + "\"equity_curve\":[{\"time\":\"2025-01-01\",\"equity\":\"10000\"},{\"time\":\"2025-06-01\",\"equity\":\"10200\"}],"
                + "\"metrics\":{}}";

        long reportId = service.submitBacktestResult(USER_ID, section8);

        assertThat(reportId).isEqualTo(200L);
        var captor = org.mockito.ArgumentCaptor.forClass(BacktestReport.class);
        verify(reportMapper).insert(captor.capture());
        BacktestReport result = captor.getValue();
        assertThat(result.getSource()).isEqualTo("PLATFORM");
        assertThat(result.getName()).isEqualTo("ma-test");
        assertThat(result.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(result.getTimeframe()).isEqualTo("1h");
        verify(tradeRecordMapper).batchInsert(any());
    }

    @Test
    void submitBacktestResult_emptyJson_throwsInvalidPayload() {
        assertThatThrownBy(() -> service.submitBacktestResult(USER_ID, ""))
                .isInstanceOf(ReportInvalidPayloadException.class);
    }

    @Test
    void submitBacktestResult_invalidJson_throwsInvalidPayload() {
        assertThatThrownBy(() -> service.submitBacktestResult(USER_ID, "{not json"))
                .isInstanceOf(ReportInvalidPayloadException.class);
    }

    @Test
    void importResult_validInput_createsReportWithImportSource() {
        doAnswer(inv -> {
                    BacktestReport r = inv.getArgument(0);
                    r.setId(101L);
                    return null;
                })
                .when(reportMapper)
                .insert(any(BacktestReport.class));

        BacktestReport result =
                service.importResult(USER_ID, "imported", null, "ETH/USDT", "4h", START, END, validTrades(), null);

        assertThat(result.getSource()).isEqualTo("IMPORT");
    }

    @Test
    void submit_nullTrades_throwsInvalidPayload() {
        assertThatThrownBy(() -> service.submit(USER_ID, "test", null, "BTC/USDT", "1h", START, END, null, null))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("trades must not be empty");
    }

    @Test
    void submit_emptyTrades_throwsInvalidPayload() {
        assertThatThrownBy(() -> service.submit(
                        USER_ID, "test", null, "BTC/USDT", "1h", START, END, Collections.emptyList(), null))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("trades must not be empty");
    }

    @Test
    void submit_tooManyTrades_throwsInvalidPayload() {
        List<TradeRecord> bigList = Collections.nCopies(10_001, validTrade("BUY", BigDecimal.TEN, BigDecimal.ONE));
        assertThatThrownBy(() -> service.submit(USER_ID, "test", null, "BTC/USDT", "1h", START, END, bigList, null))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("exceed max");
    }

    @Test
    void submit_zeroPriceTrade_throwsInvalidPayload() {
        List<TradeRecord> trades = List.of(validTrade("BUY", BigDecimal.ZERO, BigDecimal.ONE));
        assertThatThrownBy(() -> service.submit(USER_ID, "test", null, "BTC/USDT", "1h", START, END, trades, null))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("price must be > 0");
    }

    @Test
    void submit_negativeAmountTrade_throwsInvalidPayload() {
        List<TradeRecord> trades = List.of(validTrade("BUY", BigDecimal.TEN, new BigDecimal("-1")));
        assertThatThrownBy(() -> service.submit(USER_ID, "test", null, "BTC/USDT", "1h", START, END, trades, null))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("amount must be > 0");
    }

    @Test
    void submit_periodStartAfterEnd_throwsInvalidPayload() {
        assertThatThrownBy(
                        () -> service.submit(USER_ID, "test", null, "BTC/USDT", "1h", END, START, validTrades(), null))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("period start must be before end");
    }

    @Test
    void submit_nullPeriod_throwsInvalidPayload() {
        assertThatThrownBy(
                        () -> service.submit(USER_ID, "test", null, "BTC/USDT", "1h", null, END, validTrades(), null))
                .isInstanceOf(ReportInvalidPayloadException.class)
                .hasMessageContaining("period start and end must not be null");
    }

    @Test
    void submit_withEquityCurve_serializesAndPersists() {
        doAnswer(inv -> {
                    BacktestReport r = inv.getArgument(0);
                    r.setId(200L);
                    return null;
                })
                .when(reportMapper)
                .insert(any(BacktestReport.class));

        List<EquityPoint> curve =
                List.of(new EquityPoint(START, new BigDecimal("10000")), new EquityPoint(END, new BigDecimal("12000")));

        BacktestReport result =
                service.submit(USER_ID, "eq-test", null, "BTC/USDT", "1h", START, END, validTrades(), curve);

        assertThat(result.getEquityCurve()).isNotNull();
        assertThat(result.getEquityCurve()).contains("10000");
    }

    // --- listByUser ---

    @Test
    void listByUser_returnsPageDto() {
        BacktestReport r = new BacktestReport();
        r.setId(1L);
        when(reportMapper.findByUserId(eq(USER_ID), eq("BTC/USDT"), eq(10), eq(0)))
                .thenReturn(List.of(r));
        when(reportMapper.countByUserId(USER_ID, "BTC/USDT")).thenReturn(1L);

        PageDto<BacktestReport> page = service.listByUser(USER_ID, "BTC/USDT", 1, 10);

        assertThat(page.content()).hasSize(1);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void listByUser_page2_correctOffset() {
        when(reportMapper.findByUserId(eq(USER_ID), eq("BTC/USDT"), eq(10), eq(10)))
                .thenReturn(List.of());
        when(reportMapper.countByUserId(eq(USER_ID), eq("BTC/USDT"))).thenReturn(0L);

        PageDto<BacktestReport> result = service.listByUser(USER_ID, "BTC/USDT", 2, 10);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(10);
        verify(reportMapper).findByUserId(USER_ID, "BTC/USDT", 10, 10);
    }

    // --- getById ---

    @Test
    void getById_found_returnsReport() {
        BacktestReport r = new BacktestReport();
        r.setId(10L);
        r.setUserId(USER_ID);
        when(reportMapper.findById(10L)).thenReturn(r);

        BacktestReport result = service.getById(10L, USER_ID);
        assertThat(result.getId()).isEqualTo(10L);
    }

    @Test
    void getById_notFound_throwsNotFoundException() {
        when(reportMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getById(999L, USER_ID)).isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void getById_wrongUser_throwsNotFoundException() {
        BacktestReport r = new BacktestReport();
        r.setId(10L);
        r.setUserId(999L);
        when(reportMapper.findById(10L)).thenReturn(r);

        assertThatThrownBy(() -> service.getById(10L, USER_ID)).isInstanceOf(ReportNotFoundException.class);
    }

    // --- getTradeRecords ---

    @Test
    void getTradeRecords_verifiesOwnershipAndReturnsTrades() {
        BacktestReport r = new BacktestReport();
        r.setId(5L);
        r.setUserId(USER_ID);
        when(reportMapper.findById(5L)).thenReturn(r);

        TradeRecord tr = new TradeRecord();
        tr.setId(1L);
        when(tradeRecordMapper.findByReportId(5L)).thenReturn(List.of(tr));

        List<TradeRecord> result = service.getTradeRecords(5L, USER_ID);
        assertThat(result).hasSize(1);
    }

    @Test
    void getTradeRecords_wrongOwner_throwsNotFoundException() {
        BacktestReport r = new BacktestReport();
        r.setId(5L);
        r.setUserId(999L);
        when(reportMapper.findById(5L)).thenReturn(r);

        assertThatThrownBy(() -> service.getTradeRecords(5L, USER_ID)).isInstanceOf(ReportNotFoundException.class);
        verify(tradeRecordMapper, never()).findByReportId(anyLong());
    }

    // --- parseEquityCurve ---

    @Test
    void parseEquityCurve_validJson_returnsList() {
        String json = "[{\"time\":\"2025-01-01T00:00:00Z\",\"equity\":10000},"
                + "{\"time\":\"2025-06-01T00:00:00Z\",\"equity\":12000}]";
        List<EquityPoint> result = service.parseEquityCurve(json);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).equity()).isEqualByComparingTo("10000");
    }

    @Test
    void parseEquityCurve_nullInput_returnsEmptyList() {
        assertThat(service.parseEquityCurve(null)).isEmpty();
    }

    @Test
    void parseEquityCurve_blankInput_returnsEmptyList() {
        assertThat(service.parseEquityCurve("  ")).isEmpty();
    }

    @Test
    void parseEquityCurve_invalidJson_returnsEmptyList() {
        assertThat(service.parseEquityCurve("{bad json}")).isEmpty();
    }
}
