package com.kwikquant.strategy.application;

import java.time.Instant;
import java.util.Map;

/**
 * 回测执行请求(PythonSubprocessBacktestRunner 消费)。{@code serviceToken}
 * 由 Gateway issueToken 后传入,Runner 放 env WORKER_SERVICE_TOKEN。
 *
 * @param taskId 回测任务 ID
 * @param strategyId 策略 ID
 * @param strategyCodeId 代码版本 ID
 * @param userId 用户 ID
 * @param symbol 交易对(单标的回测为该标的;组合回测为逗号拼接的多标的列表,结构化列表见 {@code symbols})
 * @param symbols 组合(多标的)回测的标的列表(单标的回测为 {@code null});Worker 据此走
 *     {@code on_bars(ctx)} 组合引擎,逐标的拉数据并在共享现金池撮合
 * @param exchange 交易所
 * @param intervalValue K 线周期
 * @param startTime 回测开始
 * @param endTime 回测结束
 * @param parameters 策略参数 JSON(含 initial_capital)
 * @param serviceToken Worker 服务令牌(Gateway issueToken,Worker 调 Java REST 用)
 * @param marketType 市场类型(提交时从策略冻结的快照,存 backtest_tasks.market_type;Worker 调 /klines 用)
 * @param strategySource 策略源代码(查 strategy_codes.source_code,Worker exec 实例化 on_bar；为空时执行失败)
 * @param matchingConfig Java 撮合器实际使用的费用、滑点和保真度配置快照
 * @param pairSpecs 交易对规格快照(symbol → 币单位规格,提交执行时点从 TradingPairService 取)。
 *     Worker 接受性闸门消费(docs/matching-spec.md §9);PERP fail-closed 必有该 symbol,
 *     SPOT 可缺(引擎跳过 acceptance = 存量行为)。快照参与 reproducibility(perp-backtest-spec §7)
 */
public record BacktestRunRequest(
        long taskId,
        long strategyId,
        long strategyCodeId,
        long userId,
        String symbol,
        java.util.List<String> symbols,
        String exchange,
        String intervalValue,
        Instant startTime,
        Instant endTime,
        String parameters,
        String serviceToken,
        String marketType,
        String strategySource,
        Map<String, Object> matchingConfig,
        Map<String, PairSpecSnapshot> pairSpecs) {

    /**
     * 交易对规格快照条目(全部币单位,见 {@code market/domain/TradingPairInfo} 单位契约)。
     * 金额字段用 {@code toPlainString} 字符串——worker 侧 Decimal 化拒绝 float(JSON 数值经
     * python json 解析会变 float,精度红线)。
     *
     * <p><b>不含 contractSize</b>:张数是交易所边界概念(ArchUnit contractSize_confinedToExchangeBoundary
     * 强制),回测域内全币单位,接受性/账本/报告均无合约尺寸消费方。
     */
    public record PairSpecSnapshot(
            String symbol,
            String marketType,
            String minQty,
            String maxQty,
            String tickSize,
            String stepSize,
            Integer maxLeverage) {}
}
