package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.KwikquantApplication;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Mapper 集成测试：真实 PostgreSQL + Flyway 建表（V57 funding_rates）。 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class FundingRateMapperTest extends com.kwikquant.AbstractIntegrationTest {

    @Autowired
    FundingRateMapper mapper;

    private static FundingRateMapper.FundingRateRow row(
            String exchange,
            String symbol,
            Instant fundingTime,
            String settled,
            String predicted,
            Integer intervalSeconds,
            String source) {
        return new FundingRateMapper.FundingRateRow(
                exchange,
                symbol,
                fundingTime,
                settled != null ? new BigDecimal(settled) : null,
                predicted != null ? new BigDecimal(predicted) : null,
                intervalSeconds,
                null,
                source);
    }

    @Test
    void upsert_insertThenFindRange_roundTrips() {
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        mapper.upsert(row("OKX", "MRT1/USDT", t, "0.0001", null, 28800, "EXCHANGE"));

        List<FundingRateMapper.FundingRateRow> rows =
                mapper.findRange("OKX", "MRT1/USDT", t.minusSeconds(60), t.plusSeconds(60));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.fundingTime()).isEqualTo(t);
            assertThat(r.settledRate()).isEqualByComparingTo("0.0001");
            assertThat(r.predictedRate()).isNull();
            assertThat(r.intervalSeconds()).isEqualTo(28800);
            assertThat(r.markPrice()).isNull();
            assertThat(r.source()).isEqualTo("EXCHANGE");
        });
    }

    @Test
    void upsert_conflict_settledSurvivesPredictedOnlyRow_andSourceNotFlipped() {
        Instant t = Instant.parse("2026-09-01T08:00:00Z");
        mapper.upsert(row("OKX", "MRT2/USDT", t, "-0.0000135", null, 28800, "EXCHANGE"));
        mapper.upsert(row("OKX", "MRT2/USDT", t, null, "0.0001", null, "PROXY_BINANCE"));

        List<FundingRateMapper.FundingRateRow> rows =
                mapper.findRange("OKX", "MRT2/USDT", t.minusSeconds(60), t.plusSeconds(60));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.settledRate()).isEqualByComparingTo("-0.0000135");
            assertThat(r.predictedRate()).isEqualByComparingTo("0.0001");
            assertThat(r.intervalSeconds()).isEqualTo(28800);
            assertThat(r.source()).isEqualTo("EXCHANGE");
        });
    }

    @Test
    void upsert_conflict_newSettledWinsAndCarriesSource() {
        Instant t = Instant.parse("2026-09-01T16:00:00Z");
        mapper.upsert(row("BINANCE", "MRT3/USDT", t, "0.0001", "0.00009", null, "PROXY_BINANCE"));
        mapper.upsert(row("BINANCE", "MRT3/USDT", t, "0.00012", null, 28800, "EXCHANGE"));

        List<FundingRateMapper.FundingRateRow> rows =
                mapper.findRange("BINANCE", "MRT3/USDT", t.minusSeconds(60), t.plusSeconds(60));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.settledRate()).isEqualByComparingTo("0.00012");
            assertThat(r.predictedRate()).isEqualByComparingTo("0.00009");
            assertThat(r.intervalSeconds()).isEqualTo(28800);
            assertThat(r.source()).isEqualTo("EXCHANGE");
        });
    }

    @Test
    void batchUpsert_multiplePeriods_findRangeAscendingHalfOpen() {
        Instant t0 = Instant.parse("2026-09-02T00:00:00Z");
        Instant t1 = t0.plusSeconds(28800);
        Instant t2 = t1.plusSeconds(28800);
        mapper.batchUpsert(List.of(
                row("OKX", "MRT4/USDT", t2, "0.0003", null, null, "EXCHANGE"),
                row("OKX", "MRT4/USDT", t0, "0.0001", null, null, "EXCHANGE"),
                row("OKX", "MRT4/USDT", t1, "0.0002", null, null, "EXCHANGE")));

        // [t1, t2) 半开:含 t1 不含 t2
        assertThat(mapper.findRange("OKX", "MRT4/USDT", t1, t2))
                .singleElement()
                .satisfies(r -> assertThat(r.settledRate()).isEqualByComparingTo("0.0002"));

        List<FundingRateMapper.FundingRateRow> all = mapper.findRange("OKX", "MRT4/USDT", t0, t2.plusSeconds(1));
        assertThat(all)
                .extracting(FundingRateMapper.FundingRateRow::fundingTime)
                .containsExactly(t0, t1, t2);
    }

    @Test
    void findCoverage_emptyThenPopulated() {
        FundingRateMapper.Coverage empty = mapper.findCoverage("OKX", "MRT5/USDT");
        assertThat(empty.total()).isZero();
        assertThat(empty.settled()).isZero();
        assertThat(empty.minTime()).isNull();

        Instant t0 = Instant.parse("2026-09-03T00:00:00Z");
        Instant t1 = t0.plusSeconds(28800);
        mapper.upsert(row("OKX", "MRT5/USDT", t0, "0.0001", null, null, "EXCHANGE"));
        mapper.upsert(row("OKX", "MRT5/USDT", t1, null, "0.0002", null, "EXCHANGE"));

        FundingRateMapper.Coverage c = mapper.findCoverage("OKX", "MRT5/USDT");
        assertThat(c.total()).isEqualTo(2);
        assertThat(c.settled()).isEqualTo(1);
        assertThat(c.minTime()).isEqualTo(t0);
        assertThat(c.maxTime()).isEqualTo(t1);
    }
}
