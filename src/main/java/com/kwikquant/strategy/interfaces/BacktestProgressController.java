package com.kwikquant.strategy.interfaces;

import com.kwikquant.strategy.application.BacktestTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回测进度上报端点(Worker 通道,X-Worker-Token 鉴权)。
 *
 * <p>路径 {@code POST /api/v1/backtests/{taskId}/progress} 与 {@link BacktestController}({@code /api/v1/backtests}
 * submit + {@code /{id}} status)同 base path,靠 {@code /{taskId}/progress} 后缀区分,不冲突。与 trading 的
 * {@code BacktestOrderController}({@code /orders}、{@code /klines} 后缀)同理,但本端点属 strategy 模块:
 * BacktestTask 生命周期(含进度)归 strategy,worker 鉴权 filter 是 shared.infra 任何模块可用。
 *
 * <p>Worker(event_loop.py)逐 bar 节流(~200 bar/次)上报 processedBars/totalBars →
 * {@link BacktestTaskService#reportProgress} 写 DB + 发 WS RUNNING 增量(前端进度条)。
 * task 非 RUNNING 静默跳过(已终态不误推进度)。返 204,Worker 不消费 body。
 */
@RestController
@RequestMapping("/api/v1/backtests")
@Tag(name = "回测进度")
class BacktestProgressController {

    private final BacktestTaskService taskService;

    BacktestProgressController(BacktestTaskService taskService) {
        this.taskService = taskService;
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
