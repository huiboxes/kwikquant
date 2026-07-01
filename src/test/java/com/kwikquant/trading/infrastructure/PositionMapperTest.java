package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
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
}
