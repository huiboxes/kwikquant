package com.kwikquant.strategy.infrastructure;

/**
 * Worker {@code /health} 端点返回的存活信号快照(镜像 {@code kwikquant_worker/health_signals.py}
 * 的 {@code snapshot()} 输出)。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code status} — worker 自报状态({@code "ok"};{@code "degraded"}=on_bar 连续失败)
 *   <li>{@code lastBarAt} — 最近一次 {@code on_bar} 完成的 ms 时间戳(策略消费 bar 的活性);{@code null}=尚未收过 bar
 *   <li>{@code lastWsMsgAt} — 最近一次收到 WS 消息(ticker/kline)的 ms 时间戳;{@code null}=WS 尚未连上
 *   <li>{@code consecutiveOrderFailures} — 连续下单失败次数(成功重置为 0)
 *   <li>{@code consecutiveCallbackFailures} — 事件回调(on_fill/on_funding/on_liquidation)连续失败次数
 *       (成功重置为 0)。<b>仅观测,不进健康判定</b>——restart 通道仍只由 status/WS stale/下单失败驱动:
 *       事件是一次性 WS 推送 restart 后不重放,回调瞬时异常触发 restart 纯丢策略内存态
 *       (语义源 {@code kwikquant_worker/health_signals.py})。达阈值由
 *       {@link DockerWorkerManager#healthCheck} 出 WARN 供运维排障
 * </ul>
 *
 * <p>{@code lastBarAt}/{@code lastWsMsgAt}/{@code consecutiveOrderFailures}/{@code consecutiveCallbackFailures}
 * 用包装类型,JSON {@code null} 反序列化为 Java {@code null}(worker 刚启动、WS 未连上时各字段为
 * {@code null};旧镜像 worker 无 callbackFailures 字段 → null,消费方必须 null-safe)。判定逻辑见
 * {@link DockerWorkerManager#isWorkerHealthy}(不依赖 {@code lastBarAt},避免 bar interval 变化误判;
 * 改用持续性的 {@code lastWsMsgAt}——WS 持续推 ticker,与 interval 无关)。
 *
 * @param status worker 自报状态
 * @param lastBarAt 最近 on_bar ms 时间戳,nullable
 * @param lastWsMsgAt 最近 WS 消息 ms 时间戳,nullable
 * @param consecutiveOrderFailures 连续下单失败次数,nullable
 * @param incarnation 容器世代 UUID(Java 启动时经 WORKER_INCARNATION env 注入,/health 原样回传;
 *        旧镜像 worker 无此字段 → null,WOS 退回名字匹配语义),nullable
 * @param consecutiveCallbackFailures 事件回调连续失败次数(仅观测不判定,见上),旧镜像无此字段 →
 *        null,nullable
 */
record WorkerHealthSnapshot(
        String status,
        Long lastBarAt,
        Long lastWsMsgAt,
        Integer consecutiveOrderFailures,
        String incarnation,
        Integer consecutiveCallbackFailures) {}
