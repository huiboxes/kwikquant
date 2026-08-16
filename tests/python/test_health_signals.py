"""HealthSignals 单元测试(runner 存活信号聚合,纯逻辑无外部依赖)。"""

from __future__ import annotations

from kwikquant_worker.health_signals import HealthSignals


def test_initial_snapshot():
    """初始:status ok + strategyId 正确 + 时间戳 None + 连续失败 0。"""
    s = HealthSignals(42)
    snap = s.snapshot()
    assert snap["status"] == "ok"
    assert snap["strategyId"] == 42
    assert snap["lastBarAt"] is None
    assert snap["lastWsMsgAt"] is None
    assert snap["consecutiveOrderFailures"] == 0


def test_touch_ws_msg_sets_last_ws_msg_at():
    """touch_ws_msg 后 lastWsMsgAt 非 None(>0)。"""
    s = HealthSignals(1)
    assert s.snapshot()["lastWsMsgAt"] is None
    s.touch_ws_msg()
    assert s.snapshot()["lastWsMsgAt"] is not None
    assert s.snapshot()["lastWsMsgAt"] > 0


def test_touch_bar_sets_last_bar_at():
    """touch_bar 后 lastBarAt 非 None(>0)。"""
    s = HealthSignals(1)
    assert s.snapshot()["lastBarAt"] is None
    s.touch_bar()
    assert s.snapshot()["lastBarAt"] is not None
    assert s.snapshot()["lastBarAt"] > 0


def test_record_order_outcome_failure_accumulates():
    """record_order_outcome(ok=False) 累加(调两次→2)。"""
    s = HealthSignals(1)
    s.record_order_outcome(ok=False)
    s.record_order_outcome(ok=False)
    assert s.snapshot()["consecutiveOrderFailures"] == 2


def test_record_order_outcome_success_resets():
    """record_order_outcome(ok=True) 重置(先 False 两次再 True→0)。"""
    s = HealthSignals(1)
    s.record_order_outcome(ok=False)
    s.record_order_outcome(ok=False)
    assert s.snapshot()["consecutiveOrderFailures"] == 2
    s.record_order_outcome(ok=True)
    assert s.snapshot()["consecutiveOrderFailures"] == 0


def test_snapshot_is_isolated_from_internal_state():
    """snapshot 返新 dict,改返回值不影响内部状态(HealthServer 读线程隔离)。"""
    s = HealthSignals(7)
    s.touch_bar()
    snap = s.snapshot()
    snap["consecutiveOrderFailures"] = 999
    snap["lastBarAt"] = "tampered"
    snap2 = s.snapshot()
    assert snap2["consecutiveOrderFailures"] == 0
    assert snap2["lastBarAt"] != "tampered"
    assert isinstance(snap2["lastBarAt"], float)
