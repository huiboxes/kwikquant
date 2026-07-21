package com.kwikquant.trading.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
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
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final BacktestOrderController controller = new BacktestOrderController(service, marketDataService);

    private static BacktestOrderRequest request() {
        return new BacktestOrderRequest(
                "BTC/USDT",
                OrderSide.BUY,
                OrderType.MARKET,
                new BigDecimal("0.1"),
                null,
                MarketType.SPOT,
                Exchange.BINANCE,
                snapshot(),
                null,
                null,
                null);
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
                1,
                0,
                "BTC/USDT",
                OrderSide.BUY,
                new BigDecimal("42150"),
                new BigDecimal("0.1"),
                new BigDecimal("4.215"),
                "USDT",
                "taker",
                "ext-1",
                Instant.parse("2024-01-15T08:00:00Z"));
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

    @Test
    void klines_delegatesToFetchKlineRangeApiFirst() {
        // Task 4: GET /api/v1/backtests/{taskId}/klines 委托 MarketDataService.fetchKlineRangeApiFirst
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-01T01:00:00Z");
        Kline k = new Kline(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                Interval._1h,
                start,
                new BigDecimal("50000"),
                new BigDecimal("50100"),
                new BigDecimal("49900"),
                new BigDecimal("50050"),
                new BigDecimal("12.5"));
        when(marketDataService.fetchKlineRangeApiFirst(
                        Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end))
                .thenReturn(List.of(k));

        ApiResponse<List<Kline>> resp =
                controller.klines(42L, Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", Interval._1h, start, end);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).openTime()).isEqualTo(start);
    }
}
