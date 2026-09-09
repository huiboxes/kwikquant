package com.kwikquant.shared.types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 张↔币换算端口单测。三 ctVal 覆盖 OKX 实测形态(BTC=0.01/ETH=0.1/SOL=1);
 * 除不尽 fail-closed 是资金安全断言,不是边界美化。
 */
class PerpMathTest {

    @Test
    void toContracts_okxCtValShapes() {
        // BTC-USDT-SWAP ctVal=0.01:0.0001 BTC(最小下单量)= 0.01 张
        assertThat(PerpMath.toContracts(new BigDecimal("0.0001"), new BigDecimal("0.01")))
                .isEqualByComparingTo("0.01");
        // ETH-USDT-SWAP ctVal=0.1
        assertThat(PerpMath.toContracts(new BigDecimal("0.5"), new BigDecimal("0.1")))
                .isEqualByComparingTo("5");
        // SOL-USDT-SWAP ctVal=1:张=币
        assertThat(PerpMath.toContracts(new BigDecimal("7"), BigDecimal.ONE)).isEqualByComparingTo("7");
    }

    @Test
    void toContracts_nonTerminatingQuotient_failsClosed() {
        // 0.5 / 0.03 = 16.666… 非终止 → 拒,绝不静默取整(实际下单量会偏离意图)
        assertThatThrownBy(() -> PerpMath.toContracts(new BigDecimal("0.5"), new BigDecimal("0.03")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void toCoin_multipliesExactly() {
        // OKX 回流:fillSz=5 张 × ctVal 0.01 = 0.05 BTC
        assertThat(PerpMath.toCoin(new BigDecimal("5"), new BigDecimal("0.01"))).isEqualByComparingTo("0.05");
        assertThat(PerpMath.toCoin(new BigDecimal("0.01"), new BigDecimal("0.1")))
                .isEqualByComparingTo("0.001");
    }

    /** OKX 未成交订单恒返 fillSz="0"、双向持仓/平仓窗口有 pos="0" 行——快照回流必须能换算零值。 */
    @Test
    void toCoin_allowsZeroContracts() {
        assertThat(PerpMath.toCoin(BigDecimal.ZERO, new BigDecimal("0.01"))).isEqualByComparingTo("0");
    }

    @Test
    void roundTrip_coinToContractsToCoin_isLossless() {
        BigDecimal ctVal = new BigDecimal("0.01");
        BigDecimal coin = new BigDecimal("0.1234");
        assertThat(PerpMath.toCoin(PerpMath.toContracts(coin, ctVal), ctVal)).isEqualByComparingTo(coin);
    }

    @Test
    void rejectsInvalidInputs() {
        // toContracts 是下单边界:0/负数量单在 Order.validate 已拒,这里保持严格正数
        assertThatThrownBy(() -> PerpMath.toContracts(BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PerpMath.toContracts(new BigDecimal("0.1"), new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        // toCoin 允许 0(快照语义)但拒 null/负数
        assertThatThrownBy(() -> PerpMath.toCoin(null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PerpMath.toCoin(new BigDecimal("-1"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PerpMath.toCoin(BigDecimal.ONE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** null 入参守卫(fixtures 的 JSON 解析层表达不了 Java 枚举 null,这里直调断言)。 */
    @Test
    void rejectsNullArgumentsAtKernelEntry() {
        assertThatThrownBy(() -> PerpMath.signedDelta(null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("side must not be null");
        assertThatThrownBy(() -> PerpMath.marginBreached(null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marginBalance must not be null");
        assertThatThrownBy(() -> PerpMath.fundingAmount(PerpMath.SIDE_LONG, null, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fundingRate must not be null");
        // margin-aware 强平价:margin 允许负(资金费穿蚀)但拒 null
        assertThatThrownBy(() -> PerpMath.liquidationPriceIsolated(
                        new BigDecimal("60000"),
                        BigDecimal.ONE,
                        null,
                        PerpMath.DEFAULT_MAINT_MARGIN_RATE,
                        PerpMath.SIDE_LONG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("margin must not be null");
    }
}
