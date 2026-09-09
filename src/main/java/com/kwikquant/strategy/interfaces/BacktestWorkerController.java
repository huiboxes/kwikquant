package com.kwikquant.strategy.interfaces;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.Kline;
import com.kwikquant.market.domain.SettledFundingRate;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.strategy.application.BacktestTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
 * 回测 Worker 通道端点(Worker 经 {@code WorkerTokenFilter} X-Worker-Token 鉴权,BACKTEST token
 * 仅限本模块三个端点)。撮合本地化后,回测 worker 与 app 的 HTTP 交互仅剩:
 * <ul>
 *   <li>{@code GET /api/v1/backtests/{taskId}/klines} — 拉历史 K 线区间(数据)</li>
 *   <li>{@code GET /api/v1/backtests/{taskId}/funding-rates} — 拉已结算资金费序列(PERP,数据)</li>
 *   <li>{@code POST /api/v1/backtests/{taskId}/progress} — 逐 bar 进度上报(心跳)</li>
 * </ul>
 * 撮合不再经 HTTP(Python worker 本地引擎,{@code docs/matching-spec.md});原 trading 模块的
 * {@code POST /orders} 回测下单端点与虚拟账本已删除。
 *
 * <p>归 strategy 模块:BacktestTask 生命周期(数据拉取/进度)属回测任务,与 {@link BacktestController}
 * (submit/status)同 base path,靠 {@code /klines}、{@code /progress} 后缀区分。
 */
@RestController
@RequestMapping("/api/v1/backtests")
@Tag(name = "回测 Worker 通道")
class BacktestWorkerController {

    private final BacktestTaskService taskService;
    private final MarketDataService marketDataService;

    BacktestWorkerController(BacktestTaskService taskService, MarketDataService marketDataService) {
        this.taskService = taskService;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/{taskId}/klines")
    @Operation(
            summary = "回测拉历史 K 线(Worker 通道)",
            description = "Worker 通道(X-Worker-Token 鉴权)。请求参数(exchange/symbol/interval/marketType/区间)"
                    + "必须与任务快照一致且区间 ⊆ 任务查询区间,不一致 400/3001(防 token 当通配行情代理);"
                    + "任务非 RUNNING → 409/4009。走 fetchKlineRangeDbFirst(DB-first + API 补漏;"
                    + "拉过的区间快照落 klines 表,真复现 + 交易所抖动容错)。区间空 → 返空 list"
                    + "(worker 据此 exit 2 → Java markFailed 7304)。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "交易所不可用(6001 EXCHANGE_UNAVAILABLE)")
    public ApiResponse<List<Kline>> klines(
            @Parameter(description = "回测任务 ID", example = "128") @PathVariable long taskId,
            @Parameter(description = "交易所", example = "OKX") @RequestParam Exchange exchange,
            @Parameter(description = "市场类型", example = "SPOT") @RequestParam MarketType marketType,
            @Parameter(description = "canonical symbol,如 BTC/USDT", example = "BTC/USDT") @RequestParam String symbol,
            @Parameter(description = "K 线周期(1m|5m|15m|1h|4h|1d)", example = "1h") @RequestParam Interval interval,
            @Parameter(description = "区间起点(含,ISO-8601)", example = "2024-01-01T00:00:00Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant start,
            @Parameter(description = "区间终点(不含,ISO-8601)", example = "2024-01-01T01:00:00Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant end) {
        // 任务快照绑定校验:参数/区间必须与任务提交时冻结的快照一致(安全红线,见 service 方法 javadoc)
        taskService.requireKlineRequestWithinTask(taskId, exchange, marketType, symbol, interval, start, end);
        return ApiResponse.ok(
                marketDataService.fetchKlineRangeDbFirst(exchange, marketType, symbol, interval, start, end));
    }

    @GetMapping("/{taskId}/funding-rates")
    @Operation(
            summary = "回测拉已结算资金费序列(Worker 通道,PERP 专用)",
            description = "Worker 通道(X-Worker-Token 鉴权)。PERP 回测资金费回放数据源"
                    + "(docs/perp-backtest-spec.md §5):funding_rates 已结算行 ASC,含 source"
                    + "(PROXY_BINANCE=跨所代理,worker 统计进报告 warnings)。请求参数必须与任务"
                    + "快照一致且区间 ⊆ 任务区间+24h 前瞻缓冲(末根 bar 期次可落在任务 end 之后),"
                    + "不一致 400/3001;任务非 RUNNING → 409/4009。覆盖完整性由提交/执行预检保证,"
                    + "本端点 DB 直读不做 API 兜底;worker 侧缺期检测 fail-closed(exit 3 → 7308)。")
    public ApiResponse<List<SettledFundingRate>> fundingRates(
            @Parameter(description = "回测任务 ID", example = "128") @PathVariable long taskId,
            @Parameter(description = "交易所", example = "OKX") @RequestParam Exchange exchange,
            @Parameter(description = "市场类型(PERP)", example = "PERP") @RequestParam MarketType marketType,
            @Parameter(description = "canonical symbol,如 BTC/USDT", example = "BTC/USDT") @RequestParam String symbol,
            @Parameter(description = "区间起点(含,ISO-8601)", example = "2024-01-01T00:00:00Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant start,
            @Parameter(description = "区间终点(不含,ISO-8601;允许至任务 end+24h)", example = "2024-02-01T00:00:00Z")
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant end) {
        // 任务快照绑定校验:参数/区间必须与任务提交时冻结的快照一致(安全红线,同 klines)
        taskService.requireFundingRequestWithinTask(taskId, exchange, marketType, symbol, start, end);
        return ApiResponse.ok(marketDataService.findSettledFundingRates(exchange, symbol, start, end));
    }

    @PostMapping("/{taskId}/progress")
    @Operation(
            summary = "回测进度上报(Worker 通道)",
            description = "Worker(X-Worker-Token 鉴权)逐 bar 上报 processedBars/totalBars。"
                    + "Java 写 backtest_tasks + 发 WS RUNNING 增量(前端进度条)。"
                    + "task 非 RUNNING 静默跳过(已终态不误推进度)。返 204,Worker 不消费 body。")
    public ResponseEntity<Void> reportProgress(
            @Parameter(description = "回测任务 ID", example = "128") @PathVariable long taskId,
            @Valid @RequestBody BacktestProgressRequest req) {
        taskService.reportProgress(taskId, req.processedBars(), req.totalBars());
        return ResponseEntity.noContent().build();
    }

    record BacktestProgressRequest(@Min(0) int processedBars, @Min(1) int totalBars) {}
}
