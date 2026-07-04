package com.kwikquant.trading.interfaces;

import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.trading.application.BacktestOrderService;
import com.kwikquant.trading.domain.Fill;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 回测下单端点(§3.1)。Worker(Python 子进程)逐 bar POST order+snapshot,经 {@code WorkerTokenFilter}
 * (X-worker-Token)认证后到此,调 {@link BacktestOrderService} 撮合 + 虚拟账本,返回 Fill。
 *
 * <p>路径 {@code /api/v1/backtests/{taskId}/orders} 与 strategy 的 {@code BacktestController}
 * ({@code /api/v1/backtests} submit + {@code /{id}} status) 不冲突({@code /orders} 后缀区分)。
 */
@RestController
@RequestMapping("/api/v1/backtests")
@Tag(name = "回测下单")
public class BacktestOrderController {

    private final BacktestOrderService service;

    public BacktestOrderController(BacktestOrderService service) {
        this.service = service;
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
}
