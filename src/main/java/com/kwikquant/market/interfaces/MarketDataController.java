package com.kwikquant.market.interfaces;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.OrderBook;
import com.kwikquant.market.domain.Ticker;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.infra.ApiResponse;
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
            @Parameter(description = "交易所（枚举: BINANCE | OKX | BITGET | PAPER）", example = "BINANCE") @RequestParam
                    Exchange exchange,
            @Parameter(description = "市场类型（枚举: SPOT | FUTURES）", example = "SPOT") @RequestParam
                    MarketType marketType) {
        return ApiResponse.ok(tradingPairService.getPairs(exchange, marketType), traceId());
    }

    @GetMapping("/ticker/{exchange}/{marketType}/{symbol}")
    @Operation(
            summary = "查最新行情",
            description = "返回最新 ticker + stale 状态。persistent symbol 走 worker 内存/DB(staleThreshold 5s 判 fresh);"
                    + "非 persistent symbol 无 worker 持续推 → CCXT fetchTicker 拉单次快照,stale=true(非实时)。"
                    + "URL 中 symbol 用 \"-\" 替代 \"/\"：BTC-USDT → BTC/USDT。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用(6001 EXCHANGE_UNAVAILABLE)——非 persistent symbol fallback 拉 CCXT 失败时")
    ApiResponse<TickerResponse> ticker(
            @Parameter(description = "交易所", example = "BINANCE") @PathVariable Exchange exchange,
            @Parameter(description = "市场类型", example = "SPOT") @PathVariable MarketType marketType,
            @Parameter(description = "symbol，URL 中用-替代/，如 BTC-USDT", example = "BTC-USDT") @PathVariable
                    String symbol) {
        // URL 中 symbol 用 "-" 替代 "/"：BTC-USDT → BTC/USDT（与 STOMP topic 推送互逆）
        String canonical = symbol.replace("-", "/");
        Ticker t = marketDataService.getLatestTicker(exchange, marketType, canonical);
        boolean stale;
        if (t == null) {
            // 非 persistent symbol 无 worker 持续推 → CCXT REST 拉单次快照(不持久化,不污染 DB/latestTickers)。
            // honest:无 worker 持续推 → 非实时 → stale=true(快照语义,不伪装成 fresh)。
            // CCXT 不可达/限频 → fetchTicker 抛 ExchangeException → MarketErrorAdvice 返 502。
            t = marketDataService.fetchTicker(exchange, marketType, canonical);
            stale = true;
        } else {
            // persistent symbol:走 fresh/stale 判(staleThreshold 5s)
            stale = marketDataService.isStale(exchange, marketType, canonical);
        }
        return ApiResponse.ok(new TickerResponse(t, stale), traceId());
    }

    @GetMapping("/tickers")
    @Operation(
            summary = "批量查行情(可排序分页)",
            description = "按交易所 + 市场类型返回全量 active symbol 的批量行情快照(1 次 fetchTickers 替 N 次 fetchTicker)。"
                    + "sort 支持 quoteVolume(默认,成交额)/percentage(涨跌幅)/last(最新价),order desc(默认)/asc,"
                    + "limit 默认 200 上限 500,search 按 canonical symbol like 过滤。"
                    + "stale 全 false(快照语义,非 worker 实时性;10s Caffeine 缓存)。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用(6001 EXCHANGE_UNAVAILABLE)")
    ApiResponse<List<TickerResponse>> tickers(
            @Parameter(description = "交易所", example = "OKX") @RequestParam Exchange exchange,
            @Parameter(description = "市场类型", example = "SPOT") @RequestParam MarketType marketType,
            @Parameter(description = "排序字段:quoteVolume(默认)/percentage/last", example = "quoteVolume")
                    @RequestParam(defaultValue = "quoteVolume")
                    String sort,
            @Parameter(description = "排序方向:desc(默认)/asc", example = "desc") @RequestParam(defaultValue = "desc")
                    String order,
            @Parameter(description = "返回数量,1-500,默认 200", example = "200")
                    @RequestParam(defaultValue = "200")
                    @Min(1)
                    @Max(500)
                    int limit,
            @Parameter(description = "canonical symbol 搜索(like,如 BTC)", example = "BTC") @RequestParam(required = false)
                    String search) {
        // canonical symbols 从 TradingPairService.getPairs 拿全量(Caffeine 1h 缓存),传给 service.fetchTickers
        // 内部翻译成 ccxt unified 喂 fetchTickers。stale 全 false:batch 是此刻快照,非 worker 持续推语义。
        List<String> symbols = tradingPairService.getPairs(exchange, marketType).stream()
                .map(TradingPairInfo::symbol)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<Ticker> ts = marketDataService.fetchTickers(exchange, marketType, symbols, sort, order, limit, search);
        List<TickerResponse> resp =
                ts.stream().map(t -> new TickerResponse(t, false)).toList();
        return ApiResponse.ok(resp, traceId());
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
                    int limit,
            @Parameter(
                            description =
                                    "往前加载历史:返回 open_time < before 的最近 N 根(ISO-8601,如 2026-07-17T10:00:00Z)。省略=最近 N 根",
                            example = "2026-07-17T10:00:00Z")
                    @RequestParam(required = false)
                    String before) {
        // Instant.parse 非法串抛 DateTimeParseException → 默认 500;显式校验返 400(M1,防脏串污染监控)。
        java.time.Instant beforeInstant = null;
        if (before != null) {
            try {
                beforeInstant = java.time.Instant.parse(before);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "invalid before (expect ISO-8601 like 2026-07-17T10:00:00Z): " + before);
            }
        }
        return ApiResponse.ok(
                marketDataService.getKlines(exchange, marketType, symbol, interval, limit, beforeInstant), traceId());
    }

    @GetMapping("/orderbook/{exchange}/{marketType}/{symbol}")
    @Operation(
            summary = "查盘口深度",
            description = "返回指定交易对的买卖盘口。URL 中 symbol 用 \"-\" 替代 \"/\"：BTC-USDT → BTC/USDT。需 JWT 鉴权。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用（6001 EXCHANGE_UNAVAILABLE）")
    ApiResponse<OrderBook> orderBook(
            @Parameter(description = "交易所", example = "BINANCE") @PathVariable Exchange exchange,
            @Parameter(description = "市场类型", example = "SPOT") @PathVariable MarketType marketType,
            @Parameter(description = "symbol，URL 中用-替代/，如 BTC-USDT", example = "BTC-USDT") @PathVariable String symbol,
            @Parameter(description = "深度档数，1-100，默认 20", example = "20")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int depth) {
        SecurityUtils.currentUserId();
        String canonical = symbol.replace("-", "/");
        return ApiResponse.ok(marketDataService.fetchOrderBook(exchange, marketType, canonical, depth), traceId());
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

    @PostMapping("/subscribe/kline")
    @Operation(
            summary = "订阅 K 线",
            description =
                    "按 interval 订阅指定交易对的实时 K 线推送(WS /topic/kline/...)。需 JWT 鉴权。前端切 interval 时调,后端按需起 kline worker,idle 30s 自动退订。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用（6001 EXCHANGE_UNAVAILABLE）")
    ApiResponse<Void> subscribeKline(@Valid @RequestBody KlineSubscribeRequest req) {
        SecurityUtils.currentUserId();
        marketDataService.subscribeKline(
                req.exchange(), req.marketType(), req.symbol(), Interval.fromCcxt(req.interval()), false);
        return ApiResponse.ok(null, traceId());
    }

    @PostMapping("/unsubscribe/kline")
    @Operation(summary = "退订 K 线", description = "按 interval 退订指定交易对的 kline 推送(不影响同 symbol 的 ticker)。需 JWT 鉴权。")
    ApiResponse<Void> unsubscribeKline(@Valid @RequestBody KlineSubscribeRequest req) {
        SecurityUtils.currentUserId();
        marketDataService.unsubscribeKline(
                req.exchange(), req.marketType(), req.symbol(), Interval.fromCcxt(req.interval()));
        return ApiResponse.ok(null, traceId());
    }

    record SubscribeRequest(
            @Schema(description = "交易所（枚举: BINANCE | OKX | BITGET | PAPER）", example = "BINANCE") @NotNull
                    Exchange exchange,
            @Schema(description = "市场类型（枚举: SPOT | FUTURES）", example = "SPOT") @NotNull MarketType marketType,
            @Schema(description = "canonical symbol，如 BTC/USDT", example = "BTC/USDT") @NotBlank String symbol) {}

    /** kline 订阅请求:interval 用 ccxtValue("1m"|"5m"|"15m"|"1h"|"4h"|"1d"),与 WS destination 段一致。
     * 用 String 而非 Interval,避免 @RequestBody Jackson 默认 name() 反序列化(只认 _1m 不认 1m);
     * controller 内用 Interval.fromCcxt 转。 */
    record KlineSubscribeRequest(
            @Schema(description = "交易所（枚举: BINANCE | OKX | BITGET）", example = "OKX") @NotNull Exchange exchange,
            @Schema(description = "市场类型（枚举: SPOT | FUTURES）", example = "SPOT") @NotNull MarketType marketType,
            @Schema(description = "canonical symbol，如 BTC/USDT", example = "BTC/USDT") @NotBlank String symbol,
            @Schema(description = "K 线周期（ccxtValue: 1m|5m|15m|1h|4h|1d）", example = "15m") @NotBlank String interval) {}

    /** ticker 端点响应：携带行情快照 + stale 状态（设计 §1.3 NORMAL/STALE）。 */
    record TickerResponse(
            @Schema(description = "行情快照") Ticker ticker,
            @Schema(description = "是否过期（NORMAL/STALE 二状态）", example = "false") boolean stale) {}

    private static String traceId() {
        return MDC.get("traceId");
    }
}
