package com.kwikquant.market.interfaces;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.market.infrastructure.MarketErrorAdvice;
import com.kwikquant.shared.infra.GlobalExceptionHandler;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class MarketDataControllerTest {

    private MockMvc mockMvc;
    private MarketDataService marketDataService;
    private TradingPairService tradingPairService;

    @BeforeEach
    void setUp() {
        marketDataService = mock(MarketDataService.class);
        tradingPairService = mock(TradingPairService.class);
        var controller = new MarketDataController(marketDataService, tradingPairService);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // 让 @RequestParam Interval 接受 "1h"（同 MarketConfig 的 intervalConverter）
        var cs = new DefaultFormattingConversionService();
        cs.addConverter(String.class, Interval.class, Interval::fromCcxt);
        // SecurityUtils.currentUserId() 需要认证上下文
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("123", null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new MarketErrorAdvice())
                .setValidator(validator)
                .setConversionService(cs)
                .build();
    }

    @Test
    void pairs_whenValidParams_shouldReturnPairsList() throws Exception {
        when(tradingPairService.getPairs(Exchange.BINANCE, MarketType.SPOT))
                .thenReturn(List.of(new TradingPairInfo(
                        Exchange.BINANCE,
                        MarketType.SPOT,
                        "BTC/USDT",
                        "BTC",
                        "USDT",
                        new BigDecimal("0.001"),
                        null,
                        null,
                        null,
                        true)));

        mockMvc.perform(get("/api/v1/market/pairs").param("exchange", "BINANCE").param("marketType", "SPOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].symbol").value("BTC/USDT"));
    }

    @Test
    void pairs_whenInvalidExchange_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/market/pairs").param("exchange", "FAKE").param("marketType", "SPOT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ticker_whenExists_shouldReturnTicker() throws Exception {
        when(marketDataService.getLatestTicker(eq(Exchange.BINANCE), eq(MarketType.SPOT), eq("BTC/USDT")))
                .thenReturn(ticker());
        when(marketDataService.isStale(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT"))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/market/ticker/BINANCE/SPOT/BTC-USDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticker.symbol").value("BTC/USDT"))
                .andExpect(jsonPath("$.data.stale").value(false));
    }

    @Test
    void ticker_whenStale_shouldIncludeStaleStatus() throws Exception {
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(ticker());
        when(marketDataService.isStale(any(), any(), any())).thenReturn(true);

        mockMvc.perform(get("/api/v1/market/ticker/BINANCE/SPOT/BTC-USDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stale").value(true));
    }

    @Test
    void ticker_whenNotFound_shouldReturn404() throws Exception {
        when(marketDataService.getLatestTicker(any(), any(), any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/market/ticker/BINANCE/SPOT/DOGE-USDT")).andExpect(status().isNotFound());
    }

    @Test
    void klines_whenValidParams_shouldReturnKlines() throws Exception {
        when(marketDataService.getKlines(
                        eq(Exchange.BINANCE), eq(MarketType.SPOT), eq("BTC/USDT"), eq(Interval._1h), anyInt()))
                .thenReturn(List.of(kline()));

        mockMvc.perform(get("/api/v1/market/klines")
                        .param("exchange", "BINANCE")
                        .param("marketType", "SPOT")
                        .param("symbol", "BTC/USDT")
                        .param("interval", "1h")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].close").value(50050));
    }

    @Test
    void subscribe_whenValidRequest_shouldCallServiceSubscribeTicker() throws Exception {
        mockMvc.perform(
                        post("/api/v1/market/subscribe")
                                .contentType("application/json")
                                .content(
                                        """
                                {"exchange":"BINANCE","marketType":"SPOT","symbol":"BTC/USDT"}
                                """))
                .andExpect(status().isOk());

        verify(marketDataService).subscribeTicker(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT", false);
    }

    @Test
    void subscribe_whenInvalidBody_shouldReturn400() throws Exception {
        mockMvc.perform(
                        post("/api/v1/market/subscribe")
                                .contentType("application/json")
                                .content(
                                        """
                                {"exchange":"BINANCE","marketType":"SPOT","symbol":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsubscribe_whenValidRequest_shouldCallServiceUnsubscribe() throws Exception {
        mockMvc.perform(
                        post("/api/v1/market/unsubscribe")
                                .contentType("application/json")
                                .content(
                                        """
                                {"exchange":"BINANCE","marketType":"SPOT","symbol":"BTC/USDT"}
                                """))
                .andExpect(status().isOk());

        verify(marketDataService).unsubscribe(Exchange.BINANCE, MarketType.SPOT, "BTC/USDT");
    }

    private static Ticker ticker() {
        return new Ticker(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                new BigDecimal("50000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-06-25T10:00:00Z"),
                Instant.parse("2026-06-25T10:00:01Z"));
    }

    private static Kline kline() {
        return new Kline(
                Exchange.BINANCE,
                MarketType.SPOT,
                "BTC/USDT",
                Interval._1h,
                Instant.parse("2026-06-25T10:00:00Z"),
                new BigDecimal("50000"),
                new BigDecimal("50100"),
                new BigDecimal("49900"),
                new BigDecimal("50050"),
                new BigDecimal("12.5"));
    }
}
