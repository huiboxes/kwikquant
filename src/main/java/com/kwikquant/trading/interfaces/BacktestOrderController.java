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

/**
 * 回测下单端点(§3.1)。Worker(Python 子进程)逐 bar POST order+snapshot,经 {@code WorkerTokenFilter}
 * (X-Worker-Token)认证后到此,调 {@link BacktestOrderService} 撮合 + 虚拟账本,返回 Fill。
 *
 * <p>路径 {@code /api/v1/backtests/{taskId}/orders} 与 strategy 的 {@code BacktestController}
 * ({@code /api/v1/backtests} submit + {@code /{id}} status) 不冲突({@code /orders} 后缀区分)。
 */
@RestController
@RequestMapping("/api/v1/backtests")
public class BacktestOrderController {

    private final BacktestOrderService service;

    public BacktestOrderController(BacktestOrderService service) {
        this.service = service;
    }

    @PostMapping("/{taskId}/orders")
    public ResponseEntity<ApiResponse<Fill>> submit(
            @PathVariable long taskId, @RequestBody BacktestOrderRequest request) {
        Fill fill = service.submit(taskId, request);
        if (fill == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(ApiResponse.ok(fill));
    }
}
