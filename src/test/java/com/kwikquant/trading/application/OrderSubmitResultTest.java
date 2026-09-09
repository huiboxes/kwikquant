package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** OrderSubmitResult 单测:from() 工厂 + accessor + record equals/hashCode(删 rejected dead code 后补)。 */
class OrderSubmitResultTest {

    @Test
    void from_returnsResultWithOrderFields() {
        Order order = new Order();
        order.setId(99L);
        order.setStatus(OrderStatus.NEW);
        order.setVersion(3L);
        order.setCreatedAt(Instant.parse("2026-07-28T00:00:00Z"));
        // Order.create 对新单置 filledQty=ZERO/filledAvgPrice=null,测试镜像该初始状态
        order.setFilledQty(BigDecimal.ZERO);
        OrderSubmitResult result = OrderSubmitResult.from(order);

        assertThat(result.orderId()).isEqualTo(99L);
        assertThat(result.status()).isEqualTo(OrderStatus.NEW);
        assertThat(result.version()).isEqualTo(3L);
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-07-28T00:00:00Z"));
        // 提交时点成交信息:新单 filledQty=0、filledAvgPrice=null(成交异步推进,不承诺)
        assertThat(result.filledQty()).isEqualByComparingTo("0");
        assertThat(result.filledAvgPrice()).isNull();
    }

    @Test
    void from_carriesPointInTimeFillSnapshot() {
        // 幂等 replay / 强平单等已成交路径:from() 透出订单当前真实累计成交值
        Order order = new Order();
        order.setId(100L);
        order.setStatus(OrderStatus.FILLED);
        order.setVersion(7L);
        order.setCreatedAt(Instant.parse("2026-07-28T00:00:00Z"));
        order.setFilledQty(new BigDecimal("0.25000000"));
        order.setFilledAvgPrice(new BigDecimal("42150.50000000"));
        OrderSubmitResult result = OrderSubmitResult.from(order);

        assertThat(result.filledQty()).isEqualByComparingTo("0.25");
        assertThat(result.filledAvgPrice()).isEqualByComparingTo("42150.50");
    }

    @Test
    void accessors_returnAllComponents() {
        OrderSubmitResult result = new OrderSubmitResult(1L, OrderStatus.FILLED, 5L, Instant.EPOCH, null, null);

        assertThat(result.orderId()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(result.version()).isEqualTo(5L);
        assertThat(result.createdAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void equals_hashCode_recordSemantics() {
        Order order = new Order();
        order.setId(7L);
        order.setStatus(OrderStatus.NEW);
        order.setVersion(0L);
        order.setCreatedAt(Instant.EPOCH);
        OrderSubmitResult a = OrderSubmitResult.from(order);
        OrderSubmitResult b = OrderSubmitResult.from(order);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
