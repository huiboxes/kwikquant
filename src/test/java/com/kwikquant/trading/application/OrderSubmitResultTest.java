package com.kwikquant.trading.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderSubmitCommand;
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
        // cmd 不进结果(签名保留供未来扩展),验证不参与构造
        OrderSubmitCommand cmd =
                OrderSubmitCommand.spot(1L, "BTC/USDT", null, null, null, null, null, null, null, null, null);

        OrderSubmitResult result = OrderSubmitResult.from(order, cmd);

        assertThat(result.orderId()).isEqualTo(99L);
        assertThat(result.status()).isEqualTo(OrderStatus.NEW);
        assertThat(result.version()).isEqualTo(3L);
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-07-28T00:00:00Z"));
    }

    @Test
    void accessors_returnAllComponents() {
        OrderSubmitResult result = new OrderSubmitResult(1L, OrderStatus.FILLED, 5L, Instant.EPOCH);

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
        OrderSubmitCommand cmd =
                OrderSubmitCommand.spot(1L, "BTC/USDT", null, null, null, null, null, null, null, null, null);

        OrderSubmitResult a = OrderSubmitResult.from(order, cmd);
        OrderSubmitResult b = OrderSubmitResult.from(order, cmd);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
