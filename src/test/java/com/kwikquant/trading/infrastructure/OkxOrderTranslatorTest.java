package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link OkxOrderTranslator} 纯函数单测。无 mock,直接 verify Map 内容。
 *
 * <p>覆盖:positionEffect 四向翻译(OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT)+ SPOT(null) +
 * setLeverage/setMarginMode params + supports 路由。
 */
class OkxOrderTranslatorTest {

    private final OkxOrderTranslator translator = new OkxOrderTranslator();

    @Test
    void supports_okx_returnsTrue() {
        assertThat(translator.supports(Exchange.OKX)).isTrue();
    }

    @Test
    void supports_binanceOrBitget_returnsFalse() {
        assertThat(translator.supports(Exchange.BINANCE)).isFalse();
        assertThat(translator.supports(Exchange.BITGET)).isFalse();
        assertThat(translator.supports(Exchange.PAPER)).isFalse();
    }

    @Test
    void createOrderParams_openLong_isolated_returnsPosSideLongReduceOnlyFalseTdModeIsolated() {
        Order order = perpOrder(PositionEffect.OPEN_LONG, MarginMode.ISOLATED);
        Map<String, Object> params = translator.createOrderParams(order);
        assertThat(params).hasSize(3);
        assertThat(params.get("posSide")).isEqualTo("long");
        assertThat(params.get("reduceOnly")).isEqualTo(false);
        assertThat(params.get("tdMode")).isEqualTo("isolated");
    }

    @Test
    void createOrderParams_openShort_cross_returnsPosSideShortReduceOnlyFalseTdModeCross() {
        Order order = perpOrder(PositionEffect.OPEN_SHORT, MarginMode.CROSS);
        Map<String, Object> params = translator.createOrderParams(order);
        assertThat(params).hasSize(3);
        assertThat(params.get("posSide")).isEqualTo("short");
        assertThat(params.get("reduceOnly")).isEqualTo(false);
        assertThat(params.get("tdMode")).isEqualTo("cross");
    }

    @Test
    void createOrderParams_closeLong_isolated_returnsPosSideLongReduceOnlyTrue() {
        Order order = perpOrder(PositionEffect.CLOSE_LONG, MarginMode.ISOLATED);
        Map<String, Object> params = translator.createOrderParams(order);
        assertThat(params).hasSize(3);
        assertThat(params.get("posSide")).isEqualTo("long");
        // CLOSE_* 自动 reduceOnly=true(Order.isReduceOnly 派生)
        assertThat(params.get("reduceOnly")).isEqualTo(true);
        assertThat(params.get("tdMode")).isEqualTo("isolated");
    }

    @Test
    void createOrderParams_closeShort_cross_returnsPosSideShortReduceOnlyTrue() {
        Order order = perpOrder(PositionEffect.CLOSE_SHORT, MarginMode.CROSS);
        Map<String, Object> params = translator.createOrderParams(order);
        assertThat(params).hasSize(3);
        assertThat(params.get("posSide")).isEqualTo("short");
        assertThat(params.get("reduceOnly")).isEqualTo(true);
        assertThat(params.get("tdMode")).isEqualTo("cross");
    }

    @Test
    void createOrderParams_spot_positionEffectNull_returnsEmptyMap() {
        Order order = spotOrder();
        Map<String, Object> params = translator.createOrderParams(order);
        // SPOT 不带 posSide/reduceOnly/tdMode
        assertThat(params).isEmpty();
    }

    @Test
    void setLeverageParams_isolatedLong_returnsMgnModeIsolatedPosSideLong() {
        Map<String, Object> params = translator.setLeverageParams(MarginMode.ISOLATED, PositionSide.LONG);
        assertThat(params).hasSize(2);
        assertThat(params.get("mgnMode")).isEqualTo("isolated");
        assertThat(params.get("posSide")).isEqualTo("long");
    }

    @Test
    void setLeverageParams_crossShort_returnsMgnModeCrossPosSideShort() {
        Map<String, Object> params = translator.setLeverageParams(MarginMode.CROSS, PositionSide.SHORT);
        assertThat(params).hasSize(2);
        assertThat(params.get("mgnMode")).isEqualTo("cross");
        assertThat(params.get("posSide")).isEqualTo("short");
    }

    @Test
    void setLeverageParams_nullPosSide_omitsPosSideKey() {
        // 单向持仓模式可传 null,翻译器不写 posSide 键
        Map<String, Object> params = translator.setLeverageParams(MarginMode.ISOLATED, null);
        assertThat(params).hasSize(1).containsOnlyKeys("mgnMode");
        assertThat(params.get("mgnMode")).isEqualTo("isolated");
    }

    @Test
    void setMarginModeParams_containsLeverOnly() {
        // spike 验证:OKX setMarginMode 必须带 lever + posSide(双向持仓,否则 51000 posSide error)
        Map<String, Object> params = translator.setMarginModeParams(20, com.kwikquant.trading.domain.PositionSide.LONG);
        assertThat(params)
                .containsOnlyKeys("lever", "posSide")
                .containsEntry("lever", 20)
                .containsEntry("posSide", "long");
    }

    private static Order perpOrder(PositionEffect effect, MarginMode mode) {
        Order order = new Order();
        order.setId(1L);
        order.setSymbol("BTC/USDT");
        order.setSide(effect == PositionEffect.CLOSE_LONG ? OrderSide.SELL : OrderSide.BUY);
        order.setOrderType(OrderType.MARKET);
        order.setAmount(new BigDecimal("0.5"));
        order.setPrice(new BigDecimal("60000"));
        order.setPositionEffect(effect);
        order.setMarginMode(mode);
        order.setLeverage(10);
        return order;
    }

    private static Order spotOrder() {
        Order order = new Order();
        order.setId(2L);
        order.setSymbol("BTC/USDT");
        order.setSide(OrderSide.BUY);
        order.setOrderType(OrderType.LIMIT);
        order.setAmount(new BigDecimal("0.1"));
        order.setPrice(new BigDecimal("60000"));
        // positionEffect / marginMode / leverage = null for SPOT
        return order;
    }
}
