package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.KwikquantApplication;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
class KlineMapperTest extends com.kwikquant.AbstractIntegrationTest {

    @Autowired
    KlineMapper klineMapper;

    private static Kline kline(
            String symbol, Instant openTime, String open, String high, String low, String close, String vol) {
        return new Kline(
                Exchange.BINANCE,
                MarketType.SPOT,
                symbol,
                Interval._1m,
                openTime,
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                new BigDecimal(vol));
    }

    @Test
    void upsert_whenSameKlineTwice_shouldKeepSingleRecord() {
        var openTime = Instant.parse("2026-06-25T10:00:00Z");
        var k = kline("BTC/USDT", openTime, "50000", "50100", "49900", "50050", "12.5");
        klineMapper.upsert(KlineMapper.KlineRow.from(k));
        klineMapper.upsert(KlineMapper.KlineRow.from(k));

        List<Kline> recent = klineMapper.findRecent("BINANCE", "SPOT", "BTC/USDT", "1m", 10);
        assertThat(recent).hasSize(1);
    }

    @Test
    void upsert_whenSameOpenTimeDifferentData_shouldUpdateHighLow() {
        var openTime = Instant.parse("2026-06-25T10:01:00Z");
        klineMapper.upsert(
                KlineMapper.KlineRow.from(kline("ETH/USDT", openTime, "3000", "3010", "2990", "3005", "10")));
        // 第二次：更高 high、更低 low、不同 close/volume → GREATEST/LEAST 取极值
        klineMapper.upsert(
                KlineMapper.KlineRow.from(kline("ETH/USDT", openTime, "3000", "3050", "2950", "3008", "15")));

        List<Kline> recent = klineMapper.findRecent("BINANCE", "SPOT", "ETH/USDT", "1m", 10);
        assertThat(recent).hasSize(1);
        Kline k = recent.get(0);
        assertThat(k.high()).isEqualByComparingTo("3050");
        assertThat(k.low()).isEqualByComparingTo("2950");
        assertThat(k.close()).isEqualByComparingTo("3008");
        assertThat(k.volume()).isEqualByComparingTo("15");
    }

    @Test
    void findRecent_shouldReturnLatestNOrderedByOpenTimeDesc() {
        var t1 = Instant.parse("2026-06-25T11:00:00Z");
        var t2 = Instant.parse("2026-06-25T11:01:00Z");
        var t3 = Instant.parse("2026-06-25T11:02:00Z");
        klineMapper.upsert(KlineMapper.KlineRow.from(kline("SOL/USDT", t1, "100", "101", "99", "100", "1")));
        klineMapper.upsert(KlineMapper.KlineRow.from(kline("SOL/USDT", t2, "100", "101", "99", "101", "1")));
        klineMapper.upsert(KlineMapper.KlineRow.from(kline("SOL/USDT", t3, "100", "101", "99", "102", "1")));

        List<Kline> recent = klineMapper.findRecent("BINANCE", "SPOT", "SOL/USDT", "1m", 2);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).openTime()).isEqualTo(t3);
        assertThat(recent.get(1).openTime()).isEqualTo(t2);
    }

    @Test
    void findBefore_shouldReturnOpenTimeBeforeExcludedDescLimit() {
        var t1 = Instant.parse("2026-06-25T13:00:00Z");
        var t2 = Instant.parse("2026-06-25T13:01:00Z");
        var t3 = Instant.parse("2026-06-25T13:02:00Z");
        klineMapper.upsert(KlineMapper.KlineRow.from(kline("TRX/USDT", t1, "1", "1", "1", "1", "1")));
        klineMapper.upsert(KlineMapper.KlineRow.from(kline("TRX/USDT", t2, "1", "1", "1", "2", "1")));
        klineMapper.upsert(KlineMapper.KlineRow.from(kline("TRX/USDT", t3, "1", "1", "1", "3", "1")));

        // before=t3:返 < t3 的最近 2 根(t2, t1),DESC(t2 在前),不含 t3 本身(严格 <)
        List<Kline> before = klineMapper.findBefore("BINANCE", "SPOT", "TRX/USDT", "1m", t3, 2);
        assertThat(before).hasSize(2);
        assertThat(before.get(0).openTime()).isEqualTo(t2);
        assertThat(before.get(1).openTime()).isEqualTo(t1);
    }

    @Test
    void findBefore_whenNoEarlier_shouldReturnEmpty() {
        var t1 = Instant.parse("2026-06-25T14:00:00Z");
        klineMapper.upsert(KlineMapper.KlineRow.from(kline("LTC/USDT", t1, "1", "1", "1", "1", "1")));

        // before=t1(最早),无更早 → 空(防 fetchKlines 空死循环的 onMore 依赖此)
        List<Kline> before = klineMapper.findBefore("BINANCE", "SPOT", "LTC/USDT", "1m", t1, 10);
        assertThat(before).isEmpty();
    }
}
