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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "行情数据")
class MarketDataController {

    private final MarketDataService marketDataService;
    private final TradingPairService tradingPairService;

    MarketDataController(MarketDataService marketDataService, TradingPairService tradingPairService) {
        this.marketDataService = marketDataService;
        this.tradingPairService = tradingPairService;
    }

    @GetMapping("/pairs")
    @Operation(summary = "查询交易对列表", description = "按交易所 + 市场类型返回可交易对。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用（6001 EXCHANGE_UNAVAILABLE）")
    ApiResponse<List<TradingPairInfo>> pairs(
            @Parameter(description = "交易所（枚举: BINANCE | OKX | BYBIT | PAPER）", example = "BINANCE") @RequestParam
                    Exchange exchange,
            @Parameter(description = "市场类型（枚举: SPOT | FUTURES）", example = "SPOT") @RequestParam
                    MarketType marketType) {
        return ApiResponse.ok(tradingPairService.getPairs(exchange, marketType), traceId());
    }

    @GetMapping("/ticker/{exchange}/{marketType}/{symbol}")
    @Operation(
            summary = "查最新行情",
            description = "返回最新 ticker + stale 状态。URL 中 symbol 用 \"-\" 替代 \"/\"：BTC-USDT → BTC/USDT。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "ticker 不存在（4001 RESOURCE_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用（6001 EXCHANGE_UNAVAILABLE）")
    ApiResponse<TickerResponse> ticker(
            @Parameter(description = "交易所", example = "BINANCE") @PathVariable Exchange exchange,
            @Parameter(description = "市场类型", example = "SPOT") @PathVariable MarketType marketType,
            @Parameter(description = "symbol，URL 中用-替代/，如 BTC-USDT", example = "BTC-USDT") @PathVariable
                    String symbol) {
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
    @Operation(summary = "查历史 K 线", description = "按交易所/市场/symbol/interval 返回历史 K 线。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用（6001 EXCHANGE_UNAVAILABLE）")
    ApiResponse<List<Kline>> klines(
            @Parameter(description = "交易所", example = "BINANCE") @RequestParam Exchange exchange,
            @Parameter(description = "市场类型", example = "SPOT") @RequestParam MarketType marketType,
            @Parameter(description = "canonical symbol，如 BTC/USDT", example = "BTC/USDT") @RequestParam String symbol,
            @Parameter(description = "K 线周期（枚举: 1m|5m|15m|1h|4h|1d 等）", example = "1h") @RequestParam Interval interval,
            @Parameter(description = "返回条数，1-1000，默认 100", example = "100")
                    @RequestParam(defaultValue = "100")
                    @Min(1)
                    @Max(1000)
                    int limit) {
        return ApiResponse.ok(marketDataService.getKlines(exchange, marketType, symbol, interval, limit), traceId());
    }

    @PostMapping("/subscribe")
    @Operation(summary = "订阅行情", description = "订阅指定交易对的实时 ticker 推送（WS）。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用（6001 EXCHANGE_UNAVAILABLE）")
    ApiResponse<Void> subscribe(@Valid @RequestBody SubscribeRequest req) {
        SecurityUtils.currentUserId(); // 鉴权：未登录会抛 AccessDeniedException
        marketDataService.subscribeTicker(req.exchange(), req.marketType(), req.symbol(), false);
        return ApiResponse.ok(null, traceId());
    }

    @PostMapping("/unsubscribe")
    @Operation(summary = "退订行情", description = "取消订阅指定交易对的 ticker 推送。需 JWT 鉴权。")
    ApiResponse<Void> unsubscribe(@Valid @RequestBody SubscribeRequest req) {
        SecurityUtils.currentUserId(); // 鉴权：与 subscribe 一致
        marketDataService.unsubscribe(req.exchange(), req.marketType(), req.symbol());
        return ApiResponse.ok(null, traceId());
    }

    record SubscribeRequest(
            @Schema(description = "交易所（枚举: BINANCE | OKX | BYBIT | PAPER）", example = "BINANCE") @NotNull
                    Exchange exchange,
            @Schema(description = "市场类型（枚举: SPOT | FUTURES）", example = "SPOT") @NotNull MarketType marketType,
            @Schema(description = "canonical symbol，如 BTC/USDT", example = "BTC/USDT") @NotBlank String symbol) {}

    /** ticker 端点响应：携带行情快照 + stale 状态（设计 §1.3 NORMAL/STALE）。 */
    record TickerResponse(
            @Schema(description = "行情快照") Ticker ticker,
            @Schema(description = "是否过期（NORMAL/STALE 二状态）", example = "false") boolean stale) {}

    private static String traceId() {
        return MDC.get("traceId");
    }
}
