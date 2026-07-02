package com.kwikquant.report.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.report.domain.BacktestReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class BacktestReportMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    BacktestReportMapper mapper;

    private static final ObjectMapper OM = new ObjectMapper();

    private static long uniqueUserId() {
        return System.nanoTime() % 10_000_000L;
    }

    private static JsonNode parseJson(String json) {
        try {
            return OM.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("invalid JSON: " + json, e);
        }
    }

    private BacktestReport buildReport(long userId, String symbol) {
        BacktestReport r = new BacktestReport();
        r.setUserId(userId);
        r.setName("test-report-" + System.nanoTime());
        r.setParams("{\"fast\":14,\"slow\":28}");
        r.setSymbol(symbol);
        r.setTimeframe("1h");
        r.setPeriodStart(Instant.parse("2025-01-01T00:00:00Z"));
        r.setPeriodEnd(Instant.parse("2025-06-01T00:00:00Z"));
        r.setEquityCurve("[{\"ts\":1,\"equity\":10000},{\"ts\":2,\"equity\":10500}]");
        r.setSource("PLATFORM");
        return r;
    }

    @Test
    void insertAndFindById_roundtrip() {
        long userId = uniqueUserId();
        BacktestReport report = buildReport(userId, "BTC/USDT");
        mapper.insert(report);
        assertThat(report.getId()).isGreaterThan(0);

        BacktestReport loaded = mapper.findById(report.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getName()).isEqualTo(report.getName());
        assertThat(loaded.getSymbol()).isEqualTo("BTC/USDT");
        assertThat(loaded.getTimeframe()).isEqualTo("1h");
        assertThat(loaded.getPeriodStart()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(loaded.getPeriodEnd()).isEqualTo(Instant.parse("2025-06-01T00:00:00Z"));
        assertThat(loaded.getSource()).isEqualTo("PLATFORM");
        assertThat(loaded.getParams()).isEqualTo(report.getParams());
        // JSONB round-trip: PostgreSQL may reformat whitespace/key order
        assertThat(parseJson(loaded.getEquityCurve())).isEqualTo(parseJson(report.getEquityCurve()));
        // Metrics not yet set
        assertThat(loaded.getTotalReturn()).isNull();
        assertThat(loaded.getSharpeRatio()).isNull();
        assertThat(loaded.getMaxDrawdown()).isNull();
        assertThat(loaded.getWinRate()).isNull();
        assertThat(loaded.getProfitFactor()).isNull();
        assertThat(loaded.getTotalTrades()).isZero();
        assertThat(loaded.getAvgTradeDurationSeconds()).isZero();
        // DB-generated timestamps
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateMetrics_writesBack() {
        long userId = uniqueUserId();
        BacktestReport report = buildReport(userId, "BTC/USDT");
        mapper.insert(report);

        report.setTotalReturn(new BigDecimal("0.15230000"));
        report.setSharpeRatio(new BigDecimal("1.82000000"));
        report.setMaxDrawdown(new BigDecimal("0.05400000"));
        report.setWinRate(new BigDecimal("0.62000000"));
        report.setProfitFactor(new BigDecimal("2.35000000"));
        report.setTotalTrades(42);
        report.setAvgTradeDurationSeconds(3600L);
        mapper.updateMetrics(report);

        BacktestReport loaded = mapper.findById(report.getId());
        assertThat(loaded.getTotalReturn()).isEqualByComparingTo(new BigDecimal("0.15230000"));
        assertThat(loaded.getSharpeRatio()).isEqualByComparingTo(new BigDecimal("1.82"));
        assertThat(loaded.getMaxDrawdown()).isEqualByComparingTo(new BigDecimal("0.054"));
        assertThat(loaded.getWinRate()).isEqualByComparingTo(new BigDecimal("0.62"));
        assertThat(loaded.getProfitFactor()).isEqualByComparingTo(new BigDecimal("2.35"));
        assertThat(loaded.getTotalTrades()).isEqualTo(42);
        assertThat(loaded.getAvgTradeDurationSeconds()).isEqualTo(3600L);
        // updated_at should be refreshed by the UPDATE
        assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
    }

    @Test
    void findByUserId_paginationAndSymbolFilter() {
        long userId = uniqueUserId();
        BacktestReport btc1 = buildReport(userId, "BTC/USDT");
        BacktestReport btc2 = buildReport(userId, "BTC/USDT");
        BacktestReport eth = buildReport(userId, "ETH/USDT");
        mapper.insert(btc1);
        mapper.insert(btc2);
        mapper.insert(eth);

        // No symbol filter: all 3
        List<BacktestReport> all = mapper.findByUserId(userId, null, 10, 0);
        assertThat(all).hasSize(3);
        // Results ordered by created_at DESC
        assertThat(all.get(0).getCreatedAt()).isAfterOrEqualTo(all.get(1).getCreatedAt());

        // Symbol filter: only BTC/USDT
        List<BacktestReport> btcOnly = mapper.findByUserId(userId, "BTC/USDT", 10, 0);
        assertThat(btcOnly).hasSize(2);
        assertThat(btcOnly).allMatch(r -> "BTC/USDT".equals(r.getSymbol()));

        // Pagination: limit 1, offset 0 and offset 1
        List<BacktestReport> page1 = mapper.findByUserId(userId, null, 1, 0);
        List<BacktestReport> page2 = mapper.findByUserId(userId, null, 1, 1);
        assertThat(page1).hasSize(1);
        assertThat(page2).hasSize(1);
        assertThat(page1.get(0).getId()).isNotEqualTo(page2.get(0).getId());
    }

    @Test
    void countByUserId_correctCount() {
        long userId = uniqueUserId();
        mapper.insert(buildReport(userId, "BTC/USDT"));
        mapper.insert(buildReport(userId, "BTC/USDT"));
        mapper.insert(buildReport(userId, "ETH/USDT"));

        assertThat(mapper.countByUserId(userId, null)).isEqualTo(3);
        assertThat(mapper.countByUserId(userId, "BTC/USDT")).isEqualTo(2);
        assertThat(mapper.countByUserId(userId, "ETH/USDT")).isEqualTo(1);
        assertThat(mapper.countByUserId(userId, "SOL/USDT")).isZero();
    }

    @Test
    void findByIds_ownershipIsolation() {
        long userA = uniqueUserId();
        long userB = uniqueUserId();

        BacktestReport reportA1 = buildReport(userA, "BTC/USDT");
        BacktestReport reportA2 = buildReport(userA, "ETH/USDT");
        BacktestReport reportB1 = buildReport(userB, "BTC/USDT");
        mapper.insert(reportA1);
        mapper.insert(reportA2);
        mapper.insert(reportB1);

        List<Long> allIds = List.of(reportA1.getId(), reportA2.getId(), reportB1.getId());

        // User A can only see their own reports
        List<BacktestReport> resultA = mapper.findByIds(allIds, userA);
        assertThat(resultA).hasSize(2);
        assertThat(resultA).allMatch(r -> r.getUserId() == userA);

        // User B can only see their own reports
        List<BacktestReport> resultB = mapper.findByIds(allIds, userB);
        assertThat(resultB).hasSize(1);
        assertThat(resultB.get(0).getUserId()).isEqualTo(userB);
    }
}
