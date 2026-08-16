package com.kwikquant.strategy.infrastructure;

/**
 * Worker {@code /health} 端点返回的存活信号快照(镜像 {@code kwikquant_worker/health_signals.py}
 * 的 {@code snapshot()} 输出)。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code status} — worker 自报状态({@code "ok"};未来可扩展 {@code "degraded"}/{@code "draining"})
 *   <li>{@code lastBarAt} — 最近一次 {@code on_bar} 完成的 ms 时间戳(策略消费 bar 的活性);{@code null}=尚未收过 bar
 *   <li>{@code lastWsMsgAt} — 最近一次收到 WS 消息(ticker/kline)的 ms 时间戳;{@code null}=WS 尚未连上
 *   <li>{@code consecutiveOrderFailures} — 连续下单失败次数(成功重置为 0)
 * </ul>
 *
 * <p>{@code lastBarAt}/{@code lastWsMsgAt}/{@code consecutiveOrderFailures} 用包装类型,JSON {@code null}
 * 反序列化为 Java {@code null}(worker 刚启动、WS 未连上时各字段为 {@code null})。判定逻辑见
 * {@link DockerWorkerManager#isWorkerHealthy}(不依赖 {@code lastBarAt},避免 bar interval 变化误判;
 * 改用持续性的 {@code lastWsMsgAt}——WS 持续推 ticker,与 interval 无关)。
 *
 * @param status worker 自报状态
 * @param lastBarAt 最近 on_bar ms 时间戳,nullable
 * @param lastWsMsgAt 最近 WS 消息 ms 时间戳,nullable
 * @param consecutiveOrderFailures 连续下单失败次数,nullable
 */
record WorkerHealthSnapshot(String status, Long lastBarAt, Long lastWsMsgAt, Integer consecutiveOrderFailures) {}
