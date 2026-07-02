package com.kwikquant.strategy.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.SecurityUtils;
import com.kwikquant.strategy.application.BacktestTaskService;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskStatus;
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
class BacktestController {

    private final BacktestTaskService taskService;

    BacktestController(BacktestTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
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
    public ApiResponse<BacktestTaskDto> get(@PathVariable long id) {
        return ApiResponse.ok(BacktestTaskDto.from(taskService.getOwned(id, SecurityUtils.currentUserId())));
    }

    @GetMapping
    public ApiResponse<List<BacktestTaskDto>> list(@RequestParam long strategyId) {
        return ApiResponse.ok(taskService.listByStrategy(strategyId, SecurityUtils.currentUserId()).stream()
                .map(BacktestTaskDto::from)
                .toList());
    }

    record SubmitBacktestRequest(
            long strategyId,
            @Size(max = 20) String symbol,
            @Size(max = 20) String exchange,
            @Size(max = 10) String intervalValue,
            @NotNull Instant startTime,
            @NotNull Instant endTime,
            String parameters) {}

    record BacktestTaskDto(
            Long id,
            long strategyId,
            long strategyCodeId,
            BacktestTaskStatus status,
            String symbol,
            String exchange,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters,
            String result,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {
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
                    t.getErrorMessage(),
                    t.getCreatedAt(),
                    t.getUpdatedAt());
        }
    }
}
