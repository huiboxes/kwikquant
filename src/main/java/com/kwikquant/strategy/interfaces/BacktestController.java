package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.strategy.application.BacktestTaskService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回测任务 REST 端点。提交后立即返回 PENDING，异步执行（Wave 6 stub）+ WebSocket 推送状态。
 */
@RestController
@RequestMapping("/api/v1/backtests")
@Tag(name = "回测任务")
class BacktestController {

    private final BacktestTaskService taskService;

    BacktestController(BacktestTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @Operation(
            summary = "提交回测任务",
            description = "需 JWT 鉴权。异步提交，立即返回 PENDING 状态的 task（含 taskId）。"
                    + "策略不存在返回 404（7001）；无发布代码返回 409（7006）。"
                    + "前端用 taskId 轮询 GET /backtests/{id}，状态见 behavior-contract §3。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "策略无发布代码（7006 STRATEGY_NO_PUBLISHED_CODE）")
    public ApiResponse<BacktestTaskDto> submit(@Valid @RequestBody SubmitBacktestRequest req) {
        BacktestTask task = taskService.submit(
                req.strategyId(),
                SecurityUtils.currentUserId(),
                req.symbol(),
                req.exchange(),
                req.intervalValue(),
                req.startTime(),
                req.endTime(),
                req.parameters());
        return ApiResponse.ok(BacktestTaskDto.from(task));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查回测任务", description = "需 JWT 鉴权。用于轮询任务状态。任务不存在或非本人返回 404（7100）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "回测任务不存在或不属于当前用户（7100 BACKTEST_TASK_NOT_FOUND）")
    public ApiResponse<BacktestTaskDto> get(@Parameter(description = "任务 ID", example = "512") @PathVariable long id) {
        return ApiResponse.ok(BacktestTaskDto.from(taskService.getOwned(id, SecurityUtils.currentUserId())));
    }

    @GetMapping
    @Operation(summary = "查询策略回测任务列表", description = "需 JWT 鉴权。按策略 ID 查询其回测历史。策略不存在返回 404（7001）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    public ApiResponse<List<BacktestTaskDto>> list(
            @Parameter(description = "策略 ID", example = "128") @RequestParam long strategyId) {
        return ApiResponse.ok(taskService.listByStrategy(strategyId, SecurityUtils.currentUserId()).stream()
                .map(BacktestTaskDto::from)
                .toList());
    }

    record SubmitBacktestRequest(
            @Schema(description = "策略 ID", example = "128", requiredMode = Schema.RequiredMode.REQUIRED)
                    long strategyId,
            @Schema(description = "canonical symbol，覆盖策略默认值", example = "BTC/USDT") @Size(max = 20) String symbol,
            @Schema(description = "账户交易所(模拟盘 OKX 等,覆盖策略默认值)", example = "OKX") @Size(max = 20) String exchange,
            @Schema(description = "K 线周期", example = "1h") @Size(max = 10) String intervalValue,
            @Schema(
                            description = "回测起始时间",
                            example = "2026-06-01T00:00:00Z",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Instant startTime,
            @Schema(
                            description = "回测结束时间",
                            example = "2026-07-01T00:00:00Z",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @NotNull
                    Instant endTime,
            @Schema(description = "回测参数（JSON 字符串，键名 snake_case）", example = "{\"initial_capital\":10000}")
                    String parameters) {}

    record BacktestTaskDto(
            @Schema(description = "任务 ID，用于轮询", example = "512") Long id,
            @Schema(description = "策略 ID", example = "128") long strategyId,
            @Schema(description = "代码版本 ID", example = "256") long strategyCodeId,
            @Schema(description = "任务状态（枚举: PENDING | RUNNING | COMPLETED | FAILED）", example = "COMPLETED")
                    BacktestTaskStatus status,
            @Schema(description = "回测 symbol", example = "BTC/USDT") String symbol,
            @Schema(description = "交易所", example = "BINANCE") String exchange,
            @Schema(description = "K 线周期", example = "1h") String intervalValue,
            @Schema(description = "回测起始时间", example = "2026-06-01T00:00:00Z") Instant startTime,
            @Schema(description = "回测结束时间", example = "2026-07-01T00:00:00Z") Instant endTime,
            @Schema(description = "回测参数（JSON 字符串）") String parameters,
            @Schema(description = "回测结果（§8 JSON，COMPLETED 时有值）") String result,
            @Schema(description = "回测报告 ID（COMPLETED 时有值，task→report 导航桥梁，契约改动 B）") Long reportId,
            @Schema(description = "失败原因（FAILED 时有值）") String errorMessage,
            @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
            @Schema(description = "最后更新时间", example = "2026-07-04T12:00:05Z") Instant updatedAt) {
        static BacktestTaskDto from(BacktestTask t) {
            return new BacktestTaskDto(
                    t.getId(),
                    t.getStrategyId(),
                    t.getStrategyCodeId(),
                    t.getStatus(),
                    t.getSymbol(),
                    t.getExchange(),
                    t.getIntervalValue(),
                    t.getStartTime(),
                    t.getEndTime(),
                    t.getParameters(),
                    t.getResult(),
                    t.getReportId(),
                    t.getErrorMessage(),
                    t.getCreatedAt(),
                    t.getUpdatedAt());
        }
    }
}
