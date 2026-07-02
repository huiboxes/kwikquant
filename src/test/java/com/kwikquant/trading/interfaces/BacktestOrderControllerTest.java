package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.application.BacktestOrderService;
import com.kwikquant.trading.domain.Fill;
import com.kwikquant.trading.domain.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class BacktestOrderControllerTest {

    private final BacktestOrderService service = mock(BacktestOrderService.class);
    private final BacktestOrderController controller = new BacktestOrderController(service);

    private static BacktestOrderRequest request() {
        return new BacktestOrderRequest(
                "BTC/USDT", OrderSide.BUY, OrderType.MARKET, new BigDecimal("0.1"), null, MarketType.SPOT, Exchange.BINANCE, snapshot());
    }

    private static MarketSnapshot snapshot() {
        return new MarketSnapshot(
                Instant.parse("2024-01-15T08:00:00Z"),
                new BigDecimal("42150"),
                null,
                null,
                new BigDecimal("42150"),
                new BigDecimal("42150"),
                new BigDecimal("42050"),
                new BigDecimal("42150"),
                BigDecimal.TEN,
                List.of(),
                List.of());
    }

    @Test
    void submit_returns200Fill_whenMatched() {
        Fill fill = Fill.create(
                1, 0, "BTC/USDT", OrderSide.BUY, new BigDecimal("42150"), new BigDecimal("0.1"),
                new BigDecimal("4.215"), "USDT", "taker", "ext-1", Instant.parse("2024-01-15T08:00:00Z"));
        when(service.submit(eq(42L), any())).thenReturn(fill);

        ResponseEntity<ApiResponse<Fill>> resp = controller.submit(42L, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().data().getQty()).isEqualByComparingTo("0.1");
    }

    @Test
    void submit_returns204_whenNoFill() {
        when(service.submit(eq(42L), any())).thenReturn(null);

        ResponseEntity<ApiResponse<Fill>> resp = controller.submit(42L, request());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resp.getBody()).isNull();
    }
}
