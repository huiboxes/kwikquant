package com.kwikquant.strategy.interfaces;

import com.kwikquant.strategy.application.WorkerConfig;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Worker bootstrap 响应:runner 容器启动后 {@code GET /api/v1/worker/bootstrap} 拉取的启动配置。
 *
 * <p>字段 camelCase 对齐 {@code worker_server} {@code cfg.get("sourceCode")} 等读取口径
 * (Jackson 序列化 record 字段名 = camelCase)。**不含 serviceToken**——worker 已有 env
 * {@code WORKER_SERVICE_TOKEN},bootstrap 只下发 sourceCode + 策略参数(解 E2BIG + 不裸露 env)。
 */
public record WorkerBootstrapView(
        @Schema(description = "策略 ID", example = "128") long strategyId,
        @Schema(description = "策略名", example = "BTC 网格") String strategyName,
        @Schema(description = "策略 Python 源码") String sourceCode,
        @Schema(description = "交易对", example = "BTC/USDT") String symbol,
        @Schema(description = "交易所", example = "OKX") String exchange,
        @Schema(description = "市场类型", example = "SPOT") String marketType,
        @Schema(description = "策略级默认杠杆（PERP 订单未显式传 leverage 时的缺省值；SPOT null）", example = "10") Integer leverage,
        @Schema(description = "策略级默认保证金模式（PERP: ISOLATED | CROSS，同 leverage 缺省语义；SPOT null）", example = "ISOLATED")
                String marginMode,
        @Schema(description = "K 线周期", example = "1h") String intervalValue,
        @Schema(description = "策略参数 JSON（worker 注入策略 PARAMS/ctx.params）", example = "{}") String parameters,
        @Schema(description = "Java API 根 URL", example = "http://kwikquant-app:8080") String apiBaseUrl) {

    static WorkerBootstrapView from(WorkerConfig c) {
        return new WorkerBootstrapView(
                c.strategyId(),
                c.strategyName(),
                c.sourceCode(),
                c.symbol(),
                c.exchange(),
                c.marketType(),
                c.leverage(),
                c.marginMode(),
                c.intervalValue(),
                c.parameters(),
                c.apiBaseUrl());
    }
}
