package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kwikquant.account.application.CcxtAuthExchangeFactory;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.infrastructure.CcxtOrderAdapter.PositionSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 4a.1 契约层测试:验证 {@link PositionSnapshot} 12 字段可构造 + 访问。
 *
 * <p>4a.3 后 setLeverage/setMarginMode 已真实实现(不再抛占位 ExchangeException),对应行为测试迁到
 * {@link DefaultCcxtOrderAdapterTest}(mock Okx verify params)。本测试类只保留 PositionSnapshot record
 * 契约(纯数据,无 Spring/CCXT 依赖)。
 */
class CcxtOrderAdapterContractTest {

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

    @SuppressWarnings("unused")
    private static DefaultCcxtOrderAdapter unusedAdapterForCompilerHint() {
        // 保留构造可达性检查:4a.4 注入 3 bean(factory + translator + okxRestClient;registry 去掉因模块边界,
        // trading 不能依赖 market :: infrastructure)。防止未来误改构造导致 Spring 启动挂。
        return new DefaultCcxtOrderAdapter(
                mock(CcxtAuthExchangeFactory.class), new OkxOrderTranslator(), mock(OkxRestClient.class));
    }
}
