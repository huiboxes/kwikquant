package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.KwikquantApplication;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Mapper 集成测试：Testcontainers PostgreSQL + Flyway 真实建表。 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class TickerMapperTest extends com.kwikquant.AbstractIntegrationTest {

    @Autowired
    TickerMapper tickerMapper;

    private static Ticker ticker(String symbol, String last, Instant eventTime) {
        return new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                symbol,
                new BigDecimal(last),
                new BigDecimal("49999"),
                new BigDecimal("50001"),
                new BigDecimal("51000"),
                new BigDecimal("49000"),
                new BigDecimal("49500"),
                new BigDecimal("100"),
                new BigDecimal("5000000"),
                new BigDecimal("500"),
                new BigDecimal("1.01"),
                eventTime,
                Instant.parse("2026-06-25T10:00:01Z"));
    }

    @Test
    void upsert_whenNewTicker_shouldInsert() {
        tickerMapper.upsert(
                TickerMapper.TickerRow.from(ticker("ADA/USDT", "0.5", Instant.parse("2026-06-25T10:00:00Z"))));
        Ticker found = tickerMapper.findLatest("BINANCE", "SPOT", "ADA/USDT");
        assertThat(found).isNotNull();
        assertThat(found.last()).isEqualByComparingTo("0.5");
    }

    @Test
    void upsert_whenExistingTicker_shouldUpdateLatestPrice() {
        var t = Instant.parse("2026-06-25T10:00:00Z");
        tickerMapper.upsert(TickerMapper.TickerRow.from(ticker("DOT/USDT", "10", t)));
        tickerMapper.upsert(TickerMapper.TickerRow.from(ticker("DOT/USDT", "11", t)));
        Ticker found = tickerMapper.findLatest("BINANCE", "SPOT", "DOT/USDT");
        assertThat(found.last()).isEqualByComparingTo("11");
    }

    @Test
    void findLatest_whenExists_shouldReturnLatest() {
        tickerMapper.upsert(
                TickerMapper.TickerRow.from(ticker("LINK/USDT", "20", Instant.parse("2026-06-25T10:00:00Z"))));
        Ticker found = tickerMapper.findLatest("BINANCE", "SPOT", "LINK/USDT");
        assertThat(found).isNotNull();
        assertThat(found.exchange()).isEqualTo(Exchange.BINANCE);
        assertThat(found.symbol()).isEqualTo("LINK/USDT");
        // 验证 SQL alias 映射：last_price AS "last"、open_price AS "open" 等
        assertThat(found.last()).isEqualByComparingTo("20");
        assertThat(found.open()).isEqualByComparingTo("49500");
        assertThat(found.baseVolume()).isEqualByComparingTo("100");
        assertThat(found.timestamp()).isEqualTo(Instant.parse("2026-06-25T10:00:00Z"));
    }

    @Test
    void findLatest_whenAbsent_shouldReturnNull() {
        Ticker found = tickerMapper.findLatest("BINANCE", "SPOT", "NONEXISTENT/USDT");
        assertThat(found).isNull();
    }
}
