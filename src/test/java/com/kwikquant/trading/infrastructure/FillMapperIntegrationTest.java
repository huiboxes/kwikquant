package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.application.FillCatchupRow;
import com.kwikquant.trading.domain.Fill;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
class FillMapperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    FillMapper fillMapper;

    @Autowired
    JdbcTemplate jdbc;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    private static Fill fill(
            long orderId, long accountId, OrderSide side, String price, String qty, String fee, Instant filledAt) {
        return Fill.create(
                orderId,
                accountId,
                "BTC/USDT",
                side,
                new BigDecimal(price),
                new BigDecimal(qty),
                new BigDecimal(fee),
                "USDT",
                "maker",
                UUID.randomUUID().toString(),
                filledAt);
    }

    @Test
    void countDailyWinLoss_multiDayMixed_shouldReturnCorrectTotalAndWinDays() {
        long acct = uniqueAccountId();
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        // Day 1: OPEN_SHORT side=SELL，但净 delta 只有开仓费用，不能把卖出名义额当盈利。
        Fill openShort = fill(1001, acct, OrderSide.SELL, "100", "1", "2", Instant.parse("2026-07-01T10:00:00Z"));
        openShort.setRealizedPnlDelta(new BigDecimal("-2"));
        fillMapper.insert(openShort);

        // Day 2: CLOSE_SHORT side=BUY，方向盈利 20 - fee 3 = 17。
        Fill closeShort = fill(1002, acct, OrderSide.BUY, "80", "1", "3", Instant.parse("2026-07-02T10:00:00Z"));
        closeShort.setRealizedPnlDelta(new BigDecimal("17"));
        fillMapper.insert(closeShort);

        // Day 3: BUY 开多只有手续费成本。
        Fill buy = fill(1003, acct, OrderSide.BUY, "300", "1", "5", Instant.parse("2026-07-03T10:00:00Z"));
        buy.setRealizedPnlDelta(new BigDecimal("-5"));
        fillMapper.insert(buy);

        // Day 4: SELL 平多亏损 10，返佣 2（fee=-2）后净亏 8。
        Fill sell = fill(1004, acct, OrderSide.SELL, "290", "1", "-2", Instant.parse("2026-07-04T10:00:00Z"));
        sell.setRealizedPnlDelta(new BigDecimal("-8"));
        fillMapper.insert(sell);

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isEqualTo(4);
        assertThat(result.winDays()).isEqualTo(1); // 只有 Day 2 盈利
    }

    @Test
    void countDailyWinLoss_noFills_shouldReturnZeros() {
        long acct = uniqueAccountId();
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isZero();
        assertThat(result.winDays()).isZero();
    }

    @Test
    void countDailyWinLoss_singleDayProfitable_shouldReturnOneWinDay() {
        long acct = uniqueAccountId();
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        Fill profitable = fill(2001, acct, OrderSide.SELL, "500", "1", "2", Instant.parse("2026-07-05T10:00:00Z"));
        profitable.setRealizedPnlDelta(new BigDecimal("8"));
        fillMapper.insert(profitable);

        var result = fillMapper.countDailyWinLoss(acct, since);

        assertThat(result.totalDays()).isEqualTo(1);
        assertThat(result.winDays()).isEqualTo(1);
    }

    // ===== runner 断线增量补拉(findCommittedSince / maxCommittedFillId)=====

    /** 最小 orders 行(补拉查询 join 侧还原 position_effect/market_type),返回自增 id。 */
    private long insertOrder(long accountId, String marketType, String positionEffect) {
        Long id = jdbc.queryForObject(
                "INSERT INTO orders (account_id, symbol, side, order_type, amount, status, market_type, position_effect)"
                        + " VALUES (?, 'BTC/USDT', 'SELL', 'LIMIT', 0.5, 'FILLED', ?, ?) RETURNING id",
                Long.class,
                accountId,
                marketType,
                positionEffect);
        return id != null ? id : -1L;
    }

    /** 落 fill 行并把 created_at 回拨到安全边界(2s)之外——补拉查询可见。 */
    private long insertFillBackdated(long orderId, long accountId, String externalFillId) {
        Fill f = fill(orderId, accountId, OrderSide.SELL, "100", "1", "0.1", Instant.parse("2026-07-01T10:00:00Z"));
        f.setExternalFillId(externalFillId);
        fillMapper.insert(f);
        jdbc.update("UPDATE fills SET created_at = now() - interval '1 minute' WHERE id = ?", f.getId());
        return f.getId();
    }

    /** 落 fill 行不回拨 created_at(≈ now)——安全边界内不可见,钉死"只读已提交且过边界行"。 */
    private long insertFillFresh(long orderId, long accountId) {
        Fill f = fill(orderId, accountId, OrderSide.SELL, "100", "1", "0.1", Instant.now());
        fillMapper.insert(f);
        return f.getId();
    }

    @Test
    void findCommittedSince_ascJoinExcludesLiquidationsAndUncommitted() {
        long acct = uniqueAccountId();
        long perpOrder = insertOrder(acct, "PERP", "CLOSE_LONG");
        long spotOrder = insertOrder(acct, "SPOT", null);

        long f1 = insertFillBackdated(perpOrder, acct, UUID.randomUUID().toString());
        insertFillBackdated(perpOrder, acct, "liq-77-1700000000000"); // 强平行:走 LiquidationEvent 通道,补拉排除
        insertFillBackdated(perpOrder, acct, "bill-9"); // LIVE 强平/ADL bill 行:同上排除
        long f2 = insertFillBackdated(spotOrder, acct, null); // external_fill_id NULL 不得被 NOT LIKE 误排
        insertFillFresh(spotOrder, acct); // created_at ≈ now → 安全边界内不可见
        long otherAcct = acct + 1;
        insertFillBackdated(insertOrder(otherAcct, "SPOT", null), otherAcct, null); // 他人账户隔离

        var rows = fillMapper.findCommittedSince(acct, 0, 100);

        assertThat(rows).extracting(FillCatchupRow::id).containsExactly(f1, f2);
        FillCatchupRow first = rows.get(0);
        assertThat(first.positionEffect()).isEqualTo(PositionEffect.CLOSE_LONG); // join orders 还原
        assertThat(first.marketType()).isEqualTo(MarketType.PERP);
        assertThat(first.symbol()).isEqualTo("BTC/USDT");
        assertThat(first.side()).isEqualTo(OrderSide.SELL);
        assertThat(first.accountId()).isEqualTo(acct);
        // 全列断言:MyBatis record 构造映射按列序位置耦合,SELECT 顺序漂移会让同型列
        // (三个 BigDecimal、feeCurrency↔liquidity)静默换值——半列断言是假守护
        assertThat(first.orderId()).isEqualTo(perpOrder);
        assertThat(first.price()).isEqualByComparingTo("100");
        assertThat(first.qty()).isEqualByComparingTo("1");
        assertThat(first.fee()).isEqualByComparingTo("0.1");
        assertThat(first.feeCurrency()).isEqualTo("USDT");
        assertThat(first.liquidity()).isEqualTo("maker");
        assertThat(first.filledAt()).isEqualTo(Instant.parse("2026-07-01T10:00:00Z"));
        FillCatchupRow second = rows.get(1);
        assertThat(second.positionEffect()).isNull(); // SPOT 无 effect
        assertThat(second.marketType()).isEqualTo(MarketType.SPOT);
        assertThat(second.orderId()).isEqualTo(spotOrder);
    }

    @Test
    void findCommittedSince_cursorAndLimitPaging() {
        long acct = uniqueAccountId();
        long order = insertOrder(acct, "SPOT", null);
        long f1 = insertFillBackdated(order, acct, null);
        long f2 = insertFillBackdated(order, acct, null);
        long f3 = insertFillBackdated(order, acct, null);

        // afterId 游标:只回 id 更大的行
        assertThat(fillMapper.findCommittedSince(acct, f1, 100))
                .extracting(FillCatchupRow::id)
                .containsExactly(f2, f3);
        // limit 截断:id ASC 前 2 行(翻页由 worker 用返回 cursor 续拉)
        assertThat(fillMapper.findCommittedSince(acct, 0, 2))
                .extracting(FillCatchupRow::id)
                .containsExactly(f1, f2);
        // 游标越过全部行 → 空页(service 层回显 afterId)
        assertThat(fillMapper.findCommittedSince(acct, f3, 100)).isEmpty();
    }

    @Test
    void maxCommittedFillId_seedSkipsUncommitted_zeroWhenEmpty() {
        long acct = uniqueAccountId();
        assertThat(fillMapper.maxCommittedFillId(acct)).isZero(); // 无行 → 0(播种起点)

        long order = insertOrder(acct, "SPOT", null);
        long f1 = insertFillBackdated(order, acct, null);
        insertFillFresh(order, acct); // fresh 行不计入(与 findCommittedSince 同边界口径)

        assertThat(fillMapper.maxCommittedFillId(acct)).isEqualTo(f1);
    }
}
