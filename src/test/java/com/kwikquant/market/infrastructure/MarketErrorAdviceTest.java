package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.market.domain.MarketDataException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.junit.jupiter.api.Test;

class MarketErrorAdviceTest {

    @Test
    void handleMarketDataException_whenRetryable_shouldReturn502() {
        var advice = new MarketErrorAdvice();
        var ex = new MarketDataException("boom", true);

        ApiResponse<Void> resp = advice.handleMarketData(ex);

        assertThat(resp.code()).isEqualTo(ErrorCode.EXCHANGE_UNAVAILABLE);
        assertThat(resp.message()).isEqualTo("market data unavailable");
    }

    @Test
    void handleMarketDataException_whenNotRetryable_shouldReturn502() {
        var advice = new MarketErrorAdvice();
        var ex = new MarketDataException("fatal", false);

        ApiResponse<Void> resp = advice.handleMarketData(ex);

        assertThat(resp.code()).isEqualTo(ErrorCode.EXCHANGE_UNAVAILABLE);
    }
}
