package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter.PositionSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 4a.1 契约层测试:验证 PositionSnapshot 12 字段可构造 + 访问,DefaultCcxtOrderAdapter.setLeverage/setMarginMode
 * 占位实现抛 ExchangeException。真实实现见 4a.3/4a.4。
 */
class CcxtOrderAdapterContractTest {

    private DefaultCcxtOrderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DefaultCcxtOrderAdapter();
    }

    @Test
    void positionSnapshot_twelveFieldsPerp_constructsAndAccessors() {
        PositionSnapshot snap = new PositionSnapshot(
                "BTC/USDT:USDT",
                "long",
                new BigDecimal("0.5"),
                new BigDecimal("60000"),
                MarketType.PERP,
                PositionSide.LONG,
                10,
                MarginMode.ISOLATED,
                new BigDecimal("54000"),
                new BigDecimal("60100"),
                new BigDecimal("30"),
                new BigDecimal("50"));
        assertThat(snap.symbol()).isEqualTo("BTC/USDT:USDT");
        assertThat(snap.side()).isEqualTo("long");
        assertThat(snap.qty()).isEqualByComparingTo("0.5");
        assertThat(snap.entryPrice()).isEqualByComparingTo("60000");
        assertThat(snap.marketType()).isEqualTo(MarketType.PERP);
        assertThat(snap.positionSide()).isEqualTo(PositionSide.LONG);
        assertThat(snap.leverage()).isEqualTo(10);
        assertThat(snap.marginMode()).isEqualTo(MarginMode.ISOLATED);
        assertThat(snap.liquidationPrice()).isEqualByComparingTo("54000");
        assertThat(snap.markPrice()).isEqualByComparingTo("60100");
        assertThat(snap.maintMargin()).isEqualByComparingTo("30");
        assertThat(snap.unrealizedPnl()).isEqualByComparingTo("50");
    }

    @Test
    void positionSnapshot_spotNullableFieldsConstructs() {
        PositionSnapshot snap = new PositionSnapshot(
                "BTC/USDT",
                "long",
                new BigDecimal("1"),
                new BigDecimal("60000"),
                MarketType.SPOT,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertThat(snap.marketType()).isEqualTo(MarketType.SPOT);
        assertThat(snap.positionSide()).isNull();
        assertThat(snap.leverage()).isNull();
        assertThat(snap.marginMode()).isNull();
        assertThat(snap.liquidationPrice()).isNull();
        assertThat(snap.markPrice()).isNull();
        assertThat(snap.maintMargin()).isNull();
        assertThat(snap.unrealizedPnl()).isNull();
    }

    @Test
    void setLeverage_throwsExchangeExceptionPlaceholder() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        assertThatThrownBy(() -> adapter.setLeverage(acct, "BTC/USDT:USDT", 10, MarginMode.ISOLATED, PositionSide.LONG))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("setLeverage not implemented")
                .hasMessageContaining("4a.3");
    }

    @Test
    void setMarginMode_throwsExchangeExceptionPlaceholder() {
        ExchangeAccount acct = new ExchangeAccount();
        acct.setId(1L);
        assertThatThrownBy(() -> adapter.setMarginMode(acct, "BTC/USDT:USDT", MarginMode.CROSS))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("setMarginMode not implemented")
                .hasMessageContaining("4a.3");
    }
}
