package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.market.domain.MarketDataException;
import com.kwikquant.market.domain.SymbolNotListedException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarketType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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

    /** not-listed 是确定性输入/配置错误 → 400 VALIDATION_FAILED(不重试),区别于 502 交易所瞬态故障。 */
    @Test
    void handleSymbolNotListed_returns400ValidationFailed() throws Exception {
        var advice = new MarketErrorAdvice();
        var ex = new SymbolNotListedException(Exchange.OKX, MarketType.PERP, "DOGE/USDT");

        ApiResponse<Void> resp = advice.handleSymbolNotListed(ex);

        assertThat(resp.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(resp.message()).contains("DOGE/USDT");
        var annot = MarketErrorAdvice.class
                .getDeclaredMethod("handleSymbolNotListed", SymbolNotListedException.class)
                .getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class);
        assertThat(annot).isNotNull();
        assertThat(annot.value()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
