package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.trading.domain.FundingRateKind;
import com.kwikquant.trading.domain.FundingSettlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestPropertySource;

/**
 * FundingSettlementMapper 集成测试(PostgreSQL 16,全量 Flyway 迁移)。验证 V59 期次化落账:
 * 期次列写入/读回(funding_time/rate_kind enum/interval_seconds/mark_price)、期次幂等键
 * UNIQUE(account_id, position_id, funding_time) 撞键、NULLS DISTINCT 语义(LIVE 双向持仓
 * 同期两条 position_id null 行不误撞)、findLastFundingTime watermark(MAX)。
 *
 * <p>account_id/position_id 无 FK(对齐 V43 约定,下游表不引用上游),测试可直接 insert。
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class FundingSettlementMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    FundingSettlementMapper mapper;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    private FundingSettlement settlement(
            long accountId, Long positionId, Instant fundingTime, FundingRateKind kind, String billId) {
        FundingSettlement s = new FundingSettlement();
        s.setAccountId(accountId);
        s.setPositionId(positionId);
        s.setSymbol("BTC/USDT");
        s.setFundingRate(new BigDecimal("0.0001"));
        s.setQtyAtSettle(new BigDecimal("0.05"));
        s.setFundingAmount(new BigDecimal("-3"));
        s.setSettleTime(fundingTime);
        s.setFundingTime(fundingTime);
        s.setRateKind(kind);
        s.setIntervalSeconds(28800);
        s.setMarkPrice(new BigDecimal("60000"));
        s.setBillId(billId);
        return s;
    }

    @Test
    void insertPeriodColumns_andReadBackViaList() {
        long acct = uniqueAccountId();
        Instant t1 = Instant.parse("2026-08-05T08:00:00Z");
        FundingSettlement s = settlement(acct, 128L, t1, FundingRateKind.SETTLED, "PAPER-128-" + t1.getEpochSecond());

        mapper.insert(s);

        assertThat(s.getId()).isNotNull(); // useGeneratedKeys 回填
        List<FundingSettlement> rows = mapper.listByAccountAndSymbol(acct, "BTC/USDT", 10);
        assertThat(rows).hasSize(1);
        FundingSettlement r = rows.get(0);
        assertThat(r.getFundingTime()).isEqualTo(t1); // TIMESTAMPTZ → Instant 往返
        assertThat(r.getRateKind()).isEqualTo(FundingRateKind.SETTLED); // VARCHAR → enum 自动映射
        assertThat(r.getIntervalSeconds()).isEqualTo(28800);
        assertThat(r.getMarkPrice()).isEqualByComparingTo("60000");
        assertThat(r.getSettleTime()).isEqualTo(t1);
        assertThat(mapper.sumFundingAmountByAccountAndSymbol(acct, "BTC/USDT")).isEqualByComparingTo("-3");
    }

    @Test
    void duplicatePeriodKey_throwsDuplicateKey() {
        // V59 期次幂等键:同 account+position+funding_time 第二条 insert 撞键(bill_id 不同也撞)
        long acct = uniqueAccountId();
        Instant t1 = Instant.parse("2026-08-05T08:00:00Z");
        mapper.insert(settlement(acct, 128L, t1, FundingRateKind.SETTLED, "PAPER-128-a"));

        assertThatThrownBy(() -> mapper.insert(settlement(acct, 128L, t1, FundingRateKind.PREDICTED, "PAPER-128-b")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void nullPositionIdRows_samePeriod_doNotCollide() {
        // LIVE 双向持仓模式:同期两条 position_id null 行(不同 bill)是合法场景,
        // PG 默认 NULLS DISTINCT 使期次键不约束 null 行,幂等仍由 UNIQUE(account_id, bill_id) 管
        long acct = uniqueAccountId();
        Instant t1 = Instant.parse("2026-08-05T08:00:00Z");
        mapper.insert(settlement(acct, null, t1, FundingRateKind.SETTLED, "bill-long-1"));
        mapper.insert(settlement(acct, null, t1, FundingRateKind.SETTLED, "bill-short-1"));

        assertThat(mapper.listByAccountAndSymbol(acct, "BTC/USDT", 10)).hasSize(2);
    }

    @Test
    void findLastFundingTime_returnsMaxOrNull() {
        long acct = uniqueAccountId();
        // 无记录 → null(catch-up 走 fallback)
        assertThat(mapper.findLastFundingTime(acct, 128L)).isNull();

        Instant t1 = Instant.parse("2026-08-05T08:00:00Z");
        Instant t2 = Instant.parse("2026-08-05T16:00:00Z");
        mapper.insert(settlement(acct, 128L, t1, FundingRateKind.SETTLED, "PAPER-128-1"));
        mapper.insert(settlement(acct, 128L, t2, FundingRateKind.SETTLED, "PAPER-128-2"));
        // 别的持仓的期次不串
        mapper.insert(settlement(acct, 129L, t2.plusSeconds(28800), FundingRateKind.SETTLED, "PAPER-129-1"));

        assertThat(mapper.findLastFundingTime(acct, 128L)).isEqualTo(t2);
    }
}
