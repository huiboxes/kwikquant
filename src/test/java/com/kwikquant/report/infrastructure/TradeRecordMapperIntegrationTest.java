package com.kwikquant.report.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.report.domain.BacktestReport;
import com.kwikquant.report.domain.TradeRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class TradeRecordMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TradeRecordMapper tradeRecordMapper;

    @Autowired
    BacktestReportMapper reportMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private static long uniqueUserId() {
        return System.nanoTime() % 10_000_000L;
    }

    /** Creates and inserts a minimal BacktestReport, returning it with its generated ID. */
    private BacktestReport seedReport(long userId) {
        BacktestReport r = new BacktestReport();
        r.setUserId(userId);
        r.setName("trade-record-test-" + System.nanoTime());
        r.setParams("{}");
        r.setSymbol("BTC/USDT");
        r.setTimeframe("1h");
        r.setPeriodStart(Instant.parse("2025-01-01T00:00:00Z"));
        r.setPeriodEnd(Instant.parse("2025-06-01T00:00:00Z"));
        r.setEquityCurve("[]");
        r.setSource("PLATFORM");
        reportMapper.insert(r);
        return r;
    }

    private TradeRecord buildTrade(long reportId, Instant time, String side, String price, String amount, String fee) {
        TradeRecord t = new TradeRecord();
        t.setReportId(reportId);
        t.setTime(time);
        t.setSide(side);
        t.setPrice(new BigDecimal(price));
        t.setAmount(new BigDecimal(amount));
        t.setFee(new BigDecimal(fee));
        return t;
    }

    @Test
    void batchInsertAndFindByReportId_orderedByTime() {
        long userId = uniqueUserId();
        BacktestReport report = seedReport(userId);

        Instant t1 = Instant.parse("2025-03-01T10:00:00Z");
        Instant t2 = Instant.parse("2025-03-01T12:00:00Z");
        Instant t3 = Instant.parse("2025-03-01T14:00:00Z");

        // Insert out of time order to verify ORDER BY
        List<TradeRecord> records = List.of(
                buildTrade(report.getId(), t3, "SELL", "68000.50", "0.5", "1.25"),
                buildTrade(report.getId(), t1, "BUY", "65000.00", "1.0", "2.50"),
                buildTrade(report.getId(), t2, "BUY", "66500.00", "0.3", "0.80"));
        tradeRecordMapper.batchInsert(records);

        List<TradeRecord> loaded = tradeRecordMapper.findByReportId(report.getId());
        assertThat(loaded).hasSize(3);

        // Verify ordered by time ASC
        assertThat(loaded.get(0).getTime()).isEqualTo(t1);
        assertThat(loaded.get(1).getTime()).isEqualTo(t2);
        assertThat(loaded.get(2).getTime()).isEqualTo(t3);

        // Verify first record fields
        TradeRecord first = loaded.get(0);
        assertThat(first.getId()).isGreaterThan(0);
        assertThat(first.getReportId()).isEqualTo(report.getId());
        assertThat(first.getSide()).isEqualTo("BUY");
        assertThat(first.getPrice()).isEqualByComparingTo(new BigDecimal("65000.00"));
        assertThat(first.getAmount()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(first.getFee()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(first.getCreatedAt()).isNotNull();

        // Verify last record (SELL)
        TradeRecord last = loaded.get(2);
        assertThat(last.getSide()).isEqualTo("SELL");
        assertThat(last.getPrice()).isEqualByComparingTo(new BigDecimal("68000.50"));
        assertThat(last.getAmount()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(last.getFee()).isEqualByComparingTo(new BigDecimal("1.25"));
    }

    @Test
    void cascadeDelete_removesTradeRecordsWhenReportDeleted() {
        long userId = uniqueUserId();
        BacktestReport report = seedReport(userId);
        long reportId = report.getId();

        List<TradeRecord> records = List.of(
                buildTrade(reportId, Instant.parse("2025-04-01T10:00:00Z"), "BUY", "70000", "1.0", "2.0"),
                buildTrade(reportId, Instant.parse("2025-04-01T14:00:00Z"), "SELL", "72000", "1.0", "2.0"));
        tradeRecordMapper.batchInsert(records);

        // Verify trades exist before deletion
        assertThat(tradeRecordMapper.findByReportId(reportId)).hasSize(2);

        // Delete the parent report via raw SQL (mapper has no delete method)
        jdbcTemplate.update("DELETE FROM backtest_reports WHERE id = ?", reportId);

        // FK ON DELETE CASCADE should remove trade records
        assertThat(tradeRecordMapper.findByReportId(reportId)).isEmpty();
    }
}
