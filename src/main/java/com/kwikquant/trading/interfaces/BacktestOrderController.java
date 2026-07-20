package com.kwikquant.trading.interfaces;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.trading.application.BacktestOrderService;
import com.kwikquant.trading.domain.Fill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回测下单 + 历史 K 线端点(§3.1 + 回测数据获取重构)。Worker(Python 子进程)经 {@code WorkerTokenFilter}
 * (X-Worker-Token)认证后到此:
 * <ul>
 *   <li>{@code POST /api/v1/backtests/{taskId}/orders} — 逐 bar 下单 + 快照,撮合返回 Fill</li>
 *   <li>{@code GET /api/v1/backtests/{taskId}/klines} — 拉历史 K 线区间,委托
 *       {@link MarketDataService#fetchKlineRangeApiFirst}(API-first + Caffeine,不查 klines 表)</li>
 * </ul>
 *
 * <p>路径与 strategy 的 {@code BacktestController}({@code /api/v1/backtests} submit + {@code /{id}}
 * status)不冲突({@code /orders}、{@code /klines} 后缀区分)。
 */
@RestController
@RequestMapping("/api/v1/backtests")
@Tag(name = "回测下单")
public class BacktestOrderController {

    private final BacktestOrderService service;
    private final MarketDataService marketDataService;

    public BacktestOrderController(BacktestOrderService service, MarketDataService marketDataService) {
        this.service = service;
        this.marketDataService = marketDataService;
    }

    @PostMapping("/{taskId}/orders")
    @Operation(
            summary = "回测下单",
            description = "Worker 通道（X-Worker-Token 鉴权，filter 内直写 401/7301，不经 advice）。"
                    + "仅回测模式，account 为 pseudo。逐 bar 提交订单 + 快照，撮合返回 Fill；"
                    + "无成交（maker 未成交）返回 204。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "回测下单被拒（7302 BACKTEST_ORDER_REJECTED）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "回测任务未运行（7303 BACKTEST_TASK_NOT_RUNNING）")
    public ResponseEntity<ApiResponse<Fill>> submit(
            @Parameter(description = "回测任务 ID", example = "128") @PathVariable long taskId,
            @RequestBody BacktestOrderRequest request) {
        Fill fill = service.submit(taskId, request);
        if (fill == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(ApiResponse.ok(fill));
    }

    @GetMapping("/{taskId}/klines")
    @Operation(
            summary = "回测拉历史 K 线(Worker 通道)",
            description = "Worker 通道(X-Worker-Token 鉴权)。走 fetchKlineRangeApiFirst(API-first + Caffeine"
                    + " 缓存,不查 klines 表)。区间空 → 返空 list(worker 据此 exit 2 → Java markFailed 7304)。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用(6001 EXCHANGE_UNAVAILABLE)")
    public ApiResponse<List<Kline>> klines(
            @Parameter(description = "回测任务 ID", example = "128") @PathVariable long taskId,
            @Parameter(description = "交易所", example = "OKX") @RequestParam Exchange exchange,
            @Parameter(description = "市场类型", example = "SPOT") @RequestParam MarketType marketType,
            @Parameter(description = "canonical symbol,如 BTC/USDT", example = "BTC/USDT") @RequestParam String symbol,
            @Parameter(description = "K 线周期(1m|5m|15m|1h|4h|1d)", example = "1h") @RequestParam Interval interval,
            @Parameter(description = "区间起点(含,ISO-8601)", example = "2024-01-01T00:00:00Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @Parameter(description = "区间终点(不含,ISO-8601)", example = "2024-01-01T01:00:00Z") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        return ApiResponse.ok(
                marketDataService.fetchKlineRangeApiFirst(exchange, marketType, symbol, interval, start, end));
    }
}
