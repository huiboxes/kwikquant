package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.strategy.application.BacktestTaskService;
import com.kwikquant.strategy.application.BacktestTaskSummary;
import com.kwikquant.strategy.application.BacktestWorkerHealthChecker;
import com.kwikquant.strategy.domain.BacktestFailureCategory;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回测任务 REST 端点。提交后立即返回 PENDING,异步执行 + WebSocket 推送状态。
 */
@RestController
@RequestMapping("/api/v1/backtests")
@Tag(name = "回测任务")
class BacktestController {

    private final BacktestTaskService taskService;
    private final Optional<BacktestWorkerHealthChecker> workerHealthChecker;
    private final String runnerMode;

    BacktestController(
            BacktestTaskService taskService,
            Optional<BacktestWorkerHealthChecker> workerHealthChecker,
            @Value("${kwikquant.backtest.runner:subprocess}") String runnerMode) {
        this.taskService = taskService;
        this.workerHealthChecker = workerHealthChecker;
        this.runnerMode = runnerMode;
    }

    @GetMapping("/doctor")
    @Operation(
            summary = "回测 worker 健康自检",
            description = "需 JWT 鉴权。返回当前 runner 模式与 subprocess 自检结果(解释器/依赖是否可用),"
                    + "用于部署验收与排错。docker runner 无 subprocess 自检,available=true。")
    public ApiResponse<WorkerDoctorDto> doctor() {
        boolean available = workerHealthChecker
                .map(BacktestWorkerHealthChecker::isAvailable)
                .orElse(true);
        String detail =
                workerHealthChecker.map(BacktestWorkerHealthChecker::detail).orElse("docker runner(容器健康探针负责)");
        return ApiResponse.ok(new WorkerDoctorDto(runnerMode, available, detail));
    }

    record WorkerDoctorDto(
            @Schema(description = "runner 模式: subprocess | docker", example = "subprocess") String runner,
            @Schema(description = "worker 环境是否可用") boolean available,
            @Schema(description = "自检详情(不可用时为修复指引)") String detail) {}

    @PostMapping
    @Operation(
            summary = "提交回测任务",
            description = "需 JWT 鉴权。异步提交，立即返回 PENDING 状态的 task（含 taskId）。"
                    + "策略不存在返回 404（7001）；无发布代码返回 409（7006）。"
                    + "前端用 taskId 轮询 GET /backtests/{id}，状态机见 docs/behavior-contract.md 回测轮询协议一节。")
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
    @Operation(
            summary = "查询回测任务列表",
            description = "需 JWT 鉴权。strategyId 可选:不传返回当前用户全部回测(带 totalReturn + strategyName,"
                    + "供回测 tab 列表 rail);传则按策略过滤其回测历史(不带 totalReturn/strategyName,"
                    + "既有行为)。策略不存在返回 404(7001)。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "策略不存在（7001 STRATEGY_NOT_FOUND）")
    public ApiResponse<List<BacktestTaskDto>> list(
            @Parameter(description = "策略 ID,不传则返回当前用户全部回测", example = "128") @RequestParam(required = false)
                    Long strategyId) {
        List<BacktestTaskDto> dtos = strategyId == null
                ? taskService.listByUser(SecurityUtils.currentUserId()).stream()
                        .map(BacktestTaskDto::from)
                        .toList()
                : taskService.listByStrategy(strategyId, SecurityUtils.currentUserId()).stream()
                        .map(BacktestTaskDto::from)
                        .toList();
        return ApiResponse.ok(dtos);
    }

    /** 失败分类 → 用户可读文案(产品层,不裸透 stderr)。映射在 {@link BacktestFailureCategory#userMessage()},WS 事件同用。 */
    private static String userMessageFor(String category) {
        if (category == null) return null;
        try {
            return BacktestFailureCategory.valueOf(category).userMessage();
        } catch (IllegalArgumentException e) {
            return null; // 未识别分类字符串(历史脏数据防御),前端兜底通用文案
        }
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
                    @Size(max = 65536)
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
            @Schema(description = "回测结果 JSON（COMPLETED 时有值）") String result,
            @Schema(description = "回测报告 ID（COMPLETED 时有值，task→report 导航桥梁）") Long reportId,
            @Schema(description = "失败原因（FAILED 时有值,技术详情,排错用）") String errorMessage,
            @Schema(
                            description =
                                    "失败分类（FAILED 时有值: ENV_SETUP|MARKET_DATA|STRATEGY_CODE|QUOTA|TIMEOUT|INTERNAL）",
                            example = "MARKET_DATA",
                            nullable = true)
                    String failureCategory,
            @Schema(description = "失败用户可读文案（FAILED 时有值,按分类映射;历史记录无分类时 null 前端兜底）", nullable = true) String userMessage,
            @Schema(
                            description = "已处理 K 线数（RUNNING 时进度,worker 逐 bar 上报 processedBars;PENDING/终态可能为 null）",
                            example = "4400")
                    Integer processedBars,
            @Schema(description = "总 K 线数（RUNNING 时进度,totalBars;PENDING/终态可能为 null）", example = "8760")
                    Integer totalBars,
            @Schema(description = "创建时间", example = "2026-07-04T12:00:00Z") Instant createdAt,
            @Schema(description = "最后更新时间", example = "2026-07-04T12:00:05Z") Instant updatedAt,
            @Schema(description = "总收益率（小数,0.15=15%;全列表路径 COMPLETED 有值,供列表卡显示;" + "按策略列表路径为 null 既有行为）")
                    BigDecimal totalReturn,
            @Schema(description = "策略名称（全列表路径组装;按策略列表路径为 null 既有行为）") String strategyName) {

        /** 既有路径(submit/get/listByStrategy):totalReturn/strategyName 为 null(domain 无此字段)。 */
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
                    t.getFailureCategory(),
                    userMessageFor(t.getFailureCategory()),
                    t.getProcessedBars(),
                    t.getTotalBars(),
                    t.getCreatedAt(),
                    t.getUpdatedAt(),
                    null,
                    null);
        }

        /** 全列表路径(listByUser):从 application 层 summary 转 DTO,带 totalReturn + strategyName。 */
        static BacktestTaskDto from(BacktestTaskSummary s) {
            return new BacktestTaskDto(
                    s.id(),
                    s.strategyId(),
                    s.strategyCodeId(),
                    s.status(),
                    s.symbol(),
                    s.exchange(),
                    s.intervalValue(),
                    s.startTime(),
                    s.endTime(),
                    s.parameters(),
                    s.result(),
                    s.reportId(),
                    s.errorMessage(),
                    s.failureCategory(),
                    userMessageFor(s.failureCategory()),
                    s.processedBars(),
                    s.totalBars(),
                    s.createdAt(),
                    s.updatedAt(),
                    s.totalReturn(),
                    s.strategyName());
        }
    }
}
