package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.trading.domain.Position;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class PositionMapperTest extends AbstractIntegrationTest {

    @Autowired
    PositionMapper positionMapper;

    private static long uniqueAccountId() {
        return System.nanoTime() % 10_000_000L;
    }

    @Test
    void insertAndFindByAccountAndSymbol() {
        long acct = uniqueAccountId();
        Position p = Position.flat(acct, "BTC/USDT");
        p.setSide(Position.SIDE_LONG);
        p.setQty(new BigDecimal("0.5"));
        p.setAvgEntryPrice(new BigDecimal("42000"));
        positionMapper.insert(p);
        assertThat(p.getId()).isNotNull();

        Position loaded = positionMapper.findByAccountAndSymbol(acct, "BTC/USDT");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getSide()).isEqualTo(Position.SIDE_LONG);
        assertThat(loaded.getQty()).isEqualByComparingTo("0.5");
        assertThat(loaded.getAvgEntryPrice()).isEqualByComparingTo("42000");
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void casUpdateSuccessWhenVersionMatches() {
        long acct = uniqueAccountId();
        Position p = Position.flat(acct, "ETH/USDT");
        p.setSide(Position.SIDE_LONG);
        p.setQty(new BigDecimal("1.0"));
        p.setAvgEntryPrice(new BigDecimal("3000"));
        positionMapper.insert(p);

        p.setQty(new BigDecimal("2.0"));
        p.setAvgEntryPrice(new BigDecimal("3100"));
        assertThat(positionMapper.casUpdate(p)).isEqualTo(1);

        Position reloaded = positionMapper.findByAccountAndSymbol(acct, "ETH/USDT");
        assertThat(reloaded.getQty()).isEqualByComparingTo("2.0");
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void casUpdateFailsWhenVersionStale() {
        long acct = uniqueAccountId();
        Position p = Position.flat(acct, "SOL/USDT");
        p.setQty(BigDecimal.ZERO);
        positionMapper.insert(p);

        p.setQty(new BigDecimal("1.0"));
        positionMapper.casUpdate(p);
        // stale: version=0 but actual=1
        p.setVersion(0);
        p.setQty(new BigDecimal("2.0"));
        assertThat(positionMapper.casUpdate(p)).isZero();
    }

    @Test
    void findByAccountReturnsAllSymbols() {
        long acct = uniqueAccountId();
        Position btc = Position.flat(acct, "BTC/USDT");
        btc.setQty(new BigDecimal("0.1"));
        positionMapper.insert(btc);
        Position eth = Position.flat(acct, "ETH/USDT");
        eth.setQty(new BigDecimal("1.0"));
        positionMapper.insert(eth);

        List<Position> all = positionMapper.findByAccount(acct);
        assertThat(all).hasSize(2);
    }

    /** 删除某账户所有持仓(重置模拟盘用)。 */
    @Test
    void deleteByAccount_removesAllPositionsForAccount() {
        long acct = uniqueAccountId();
        Position btc = Position.flat(acct, "BTC/USDT");
        btc.setQty(new BigDecimal("0.1"));
        positionMapper.insert(btc);
        Position eth = Position.flat(acct, "ETH/USDT");
        eth.setQty(new BigDecimal("1.0"));
        positionMapper.insert(eth);
        assertThat(positionMapper.findByAccount(acct)).hasSize(2);

        int affected = positionMapper.deleteByAccount(acct);
        assertThat(affected).isEqualTo(2);
        assertThat(positionMapper.findByAccount(acct)).isEmpty();
    }

    /**
     * HIGH-4 修复:账户同 symbol 持 SPOT(margin_mode NULL)+ PERP(ISOLATED) 时,
     * findByAccountAndSymbol 旧 SQL 无 margin_mode 过滤返多行 → MyBatis selectOne 抛
     * TooManyResultsException → PositionService.applyFill SPOT 分支(line 111)崩,SPOT 成交丢失。
     * 修后 SQL 加 AND margin_mode IS NULL 只返 SPOT 行。
     */
    @Test
    void findByAccountAndSymbol_returnsOnlySpotWhenPerpAlsoExists() {
        long acct = uniqueAccountId();
        Position spot = Position.flat(acct, "BTC/USDT");
        spot.setSide(Position.SIDE_LONG);
        spot.setQty(new BigDecimal("0.5"));
        spot.setAvgEntryPrice(new BigDecimal("42000"));
        positionMapper.insert(spot);

        Position perp = Position.flat(acct, "BTC/USDT");
        perp.setSide(Position.SIDE_LONG);
        perp.setQty(new BigDecimal("0.1"));
        perp.setAvgEntryPrice(new BigDecimal("42000"));
        perp.setMarginMode(MarginMode.ISOLATED);
        perp.setPositionSide("LONG");
        perp.setLeverage(10);
        positionMapper.insert(perp);

        Position loaded = positionMapper.findByAccountAndSymbol(acct, "BTC/USDT");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getMarginMode()).isNull();
        assertThat(loaded.getQty()).isEqualByComparingTo("0.5");
    }

    /**
     * HIGH-4b:findAllByAccountAndSymbol 返 SPOT+PERP 全部(供 GET /positions?symbol=)。
     * 旧 findByAccountAndSymbol 单行 SPOT-only 在只持 PERP 时返空,违反 endpoint 契约。
     */
    @Test
    void findAllByAccountAndSymbol_returnsSpotAndPerpRows() {
        long acct = uniqueAccountId();
        Position spot = Position.flat(acct, "BTC/USDT");
        spot.setSide(Position.SIDE_LONG);
        spot.setQty(new BigDecimal("0.5"));
        spot.setAvgEntryPrice(new BigDecimal("42000"));
        positionMapper.insert(spot);
        Position perp = Position.flat(acct, "BTC/USDT");
        perp.setSide(Position.SIDE_LONG);
        perp.setQty(new BigDecimal("0.1"));
        perp.setAvgEntryPrice(new BigDecimal("42000"));
        perp.setMarginMode(MarginMode.ISOLATED);
        perp.setPositionSide("LONG");
        perp.setLeverage(10);
        positionMapper.insert(perp);

        List<Position> all = positionMapper.findAllByAccountAndSymbol(acct, "BTC/USDT");
        assertThat(all).hasSize(2);
        // NULLS FIRST:SPOT(margin_mode NULL)在前,PERP(ISOLATED)在后
        assertThat(all.get(0).getMarginMode()).isNull();
        assertThat(all.get(0).getQty()).isEqualByComparingTo("0.5");
        assertThat(all.get(1).getMarginMode()).isEqualTo(MarginMode.ISOLATED);
    }
}
