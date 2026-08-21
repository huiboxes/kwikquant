"""HealthSignals — runner 运行时存活信号聚合(供 /health 端点 + Java 编排器 HTTP 探活)。

为何独立于 RunnerContext:RunnerContext 暴露给用户策略 ``on_bar(bar, ctx)`` —— 策略代码直接拿到
ctx,故健康监控字段绝不可进 RunnerContext(否则污染策略 API 边界、暴露内部状态)。HealthSignals
由编排三路写入、HealthServer 一路读出,RunnerContext 与 RunnerEventLoop 各持 private 引用、通过
private 调用上报,策略代码看不到。

三方并发:RunnerEventLoop._on_kline(asyncio loop 线程,touch)、RunnerContext.place_order
(asyncio.to_thread 线程,record)、HealthServer 独立线程(snapshot 读)。Lock 保护连续失败计数的
读-改-写累加;时间戳单调赋值,快照读半更新值无实际风险但仍纳入锁以保一致快照。

字段(epoch ms):``lastBarAt``=最近一次 bar 关闭驱动 on_bar 的时刻;``lastWsMsgAt``=最近一次
WS kline 消息到达;``consecutiveOrderFailures``=连续下单失败(成功重置 0)。Java 探活据此判
"bar 是否在流 / WS 是否在线 / 下单是否连续失败",替代 docker inspect 只能查"容器在不在"。
"""

from __future__ import annotations

import threading
import time


class HealthSignals:
    """线程安全的 runner 存活信号聚合。"""

    def __init__(self, strategy_id: int, incarnation: str | None = None) -> None:
        self._strategy_id = strategy_id
        # 容器世代:Java 每次 createAndStart 生成新 UUID 经 WORKER_INCARNATION env 注入,
        # /health 原样回传,WOS 据此把探活快照与 registry 中的当前容器匹配(容器名
        # strategy-worker-{id} 跨重启复用,快照归属不能靠名字)。None=旧契约/直接构造。
        self._incarnation = incarnation
        self._last_bar_at: float | None = None
        self._last_ws_msg_at: float | None = None
        self._consecutive_order_failures = 0
        self._consecutive_on_bar_failures = 0
        self._lock = threading.Lock()

    def touch_ws_msg(self) -> None:
        """WS kline/ticker 消息到达(RunnerEventLoop._on_kline 入口调)。"""
        with self._lock:
            self._last_ws_msg_at = time.time() * 1000.0

    def touch_bar(self) -> None:
        """bar 关闭已驱动 on_bar(RunnerEventLoop._invoke_on_bar 结束调,finally)。"""
        with self._lock:
            self._last_bar_at = time.time() * 1000.0

    def record_order_outcome(self, *, ok: bool) -> None:
        """下单结果(RunnerContext.place_order 调):成功重置 0,失败累加。"""
        with self._lock:
            if ok:
                self._consecutive_order_failures = 0
            else:
                self._consecutive_order_failures += 1

    def record_on_bar_outcome(self, *, ok: bool) -> None:
        """策略 on_bar 结果：失败时降级健康状态，下一次成功后恢复。"""
        with self._lock:
            if ok:
                self._consecutive_on_bar_failures = 0
            else:
                self._consecutive_on_bar_failures += 1

    def snapshot(self) -> dict:
        """HealthServer status_provider 调,返 /health JSON。"""
        with self._lock:
            return {
                "status": "degraded" if self._consecutive_on_bar_failures else "ok",
                "strategyId": self._strategy_id,
                "incarnation": self._incarnation,
                "lastBarAt": self._last_bar_at,
                "lastWsMsgAt": self._last_ws_msg_at,
                "consecutiveOrderFailures": self._consecutive_order_failures,
                "consecutiveOnBarFailures": self._consecutive_on_bar_failures,
            }
