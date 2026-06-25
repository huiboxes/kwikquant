package com.kwikquant.market.interfaces;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ResourceNotFoundException;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market")
class MarketDataController {

    private final MarketDataService marketDataService;
    private final TradingPairService tradingPairService;

    MarketDataController(MarketDataService marketDataService, TradingPairService tradingPairService) {
        this.marketDataService = marketDataService;
        this.tradingPairService = tradingPairService;
    }

    @GetMapping("/pairs")
    ApiResponse<List<TradingPairInfo>> pairs(@RequestParam Exchange exchange, @RequestParam MarketType marketType) {
        return ApiResponse.ok(tradingPairService.getPairs(exchange, marketType), traceId());
    }

    @GetMapping("/ticker/{exchange}/{marketType}/{symbol}")
    ApiResponse<TickerResponse> ticker(
            @PathVariable Exchange exchange, @PathVariable MarketType marketType, @PathVariable String symbol) {
        // URL 中 symbol 用 "-" 替代 "/"：BTC-USDT → BTC/USDT（与 STOMP topic 推送互逆）
        String canonical = symbol.replace("-", "/");
        Ticker t = marketDataService.getLatestTicker(exchange, marketType, canonical);
        if (t == null) {
            throw new ResourceNotFoundException("ticker");
        }
        // 暴露 stale 状态：设计 §1.3 NORMAL/STALE 二状态质量守卫在 REST 层落地
        boolean stale = marketDataService.isStale(exchange, marketType, canonical);
        return ApiResponse.ok(new TickerResponse(t, stale), traceId());
    }

    @GetMapping("/klines")
    ApiResponse<List<Kline>> klines(
            @RequestParam Exchange exchange,
            @RequestParam MarketType marketType,
            @RequestParam String symbol,
            @RequestParam Interval interval,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return ApiResponse.ok(marketDataService.getKlines(exchange, marketType, symbol, interval, limit), traceId());
    }

    @PostMapping("/subscribe")
    ApiResponse<Void> subscribe(@Valid @RequestBody SubscribeRequest req) {
        SecurityUtils.currentUserId(); // 鉴权：未登录会抛 AccessDeniedException
        marketDataService.subscribeTicker(req.exchange(), req.marketType(), req.symbol(), false);
        return ApiResponse.ok(null, traceId());
    }

    @PostMapping("/unsubscribe")
    ApiResponse<Void> unsubscribe(@Valid @RequestBody SubscribeRequest req) {
        SecurityUtils.currentUserId(); // 鉴权：与 subscribe 一致
        marketDataService.unsubscribe(req.exchange(), req.marketType(), req.symbol());
        return ApiResponse.ok(null, traceId());
    }

    record SubscribeRequest(@NotNull Exchange exchange, @NotNull MarketType marketType, @NotBlank String symbol) {}

    /** ticker 端点响应：携带行情快照 + stale 状态（设计 §1.3 NORMAL/STALE）。 */
    record TickerResponse(Ticker ticker, boolean stale) {}

    private static String traceId() {
        return MDC.get("traceId");
    }
}
