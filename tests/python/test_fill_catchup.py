"""on_fill 断线增量补拉 — FillCatchup + RunnerEventLoop 补拉循环 + _on_fill_ws fillId 去重。

契约锚点:GET /api/v1/worker/fills-since(RUNNER token,账户服务端收口;afterId 缺省=播种,
页非空 cursor=页内末行 id,空页回显);补拉行与 WS FillEvent 载荷键同构(FillCatchupDto 对齐
ws-contract 3.4,金额为 decimal string);进程内 exactly-once 派发(fillId 去重兜 WS×补拉
双流交叠);播种失败绝不以游标 0 拉取(会回放账户全部成交历史)。
"""

from __future__ import annotations

import asyncio
from decimal import Decimal
from unittest.mock import MagicMock

import pytest

import kwikquant_worker.event_loop as el
from kwikquant_worker.context import FillEvent
from kwikquant_worker.event_loop import (
    CATCHUP_PAGE_LIMIT,
    CATCHUP_SEEN_MAX,
    FillCatchup,
    RunnerEventLoop,
)

D = Decimal


class FakeClient:
    """仿 kwikquant.client.Client.get(handler 决定响应),记录 (path, params) 调用序列。"""

    def __init__(self, handler):
        self.handler = handler
        self.calls: list[tuple] = []

    def get(self, path, *, params=None, timeout=None):
        self.calls.append((path, params))
        return self.handler(path, params)


def _row(fill_id: int, symbol: str = "BTC/USDT", market_type: str = "SPOT") -> dict:
    """REST 补拉行(FillCatchupDto JSON 形态:金额 decimal string,键与 WS 载荷同构)。"""
    return {
        "fillId": fill_id,
        "orderId": 5,
        "accountId": 7,
        "symbol": symbol,
        "side": "buy",
        "price": "42150.50",
        "qty": "0.1",
        "fee": "0.4",
        "feeCurrency": "USDT",
        "liquidity": "taker",
        "positionEffect": None,
        "marketType": market_type,
        "filledAt": "2026-07-04T12:00:05Z",
    }


# ---------- FillCatchup 组件 ----------


def test_seed_returns_cursor_without_params():
    client = FakeClient(lambda path, params: {"fills": [], "cursor": 55})
    assert FillCatchup(client).seed() == 55
    assert client.calls == [("/api/v1/worker/fills-since", None)]


def test_fetch_page_passes_cursor_and_limit():
    rows = [_row(6)]
    client = FakeClient(lambda path, params: {"fills": rows, "cursor": 6})
    got_rows, cursor = FillCatchup(client).fetch_page(3)
    assert got_rows == rows
    assert cursor == 6
    assert client.calls == [
        ("/api/v1/worker/fills-since", {"afterId": 3, "limit": CATCHUP_PAGE_LIMIT})
    ]


def test_fetch_page_missing_cursor_echoes_after_id():
    # 防御:响应缺 cursor(不应发生)→ 回显 afterId,游标不前进不乱跳
    client = FakeClient(lambda path, params: {"fills": []})
    assert FillCatchup(client).fetch_page(9) == ([], 9)


def test_seed_missing_cursor_raises_never_defaults_zero():
    # 服务端契约破坏(缺 cursor 键)→ raise 进播种重试分支;绝不回退 0——
    # 未播种游标会回放账户全部成交历史(与循环侧"绝不用未播种游标拉取"红线同源)
    client = FakeClient(lambda p, q: {"fills": []})
    with pytest.raises(ValueError, match="cursor"):
        FillCatchup(client).seed()


def test_mark_seen_dedup_and_bounded_eviction():
    fc = FillCatchup(FakeClient(lambda p, q: {}))
    assert fc.mark_seen(1) is True
    assert fc.mark_seen(1) is False  # 重复 → False(派发侧跳过)
    for i in range(2, CATCHUP_SEEN_MAX + 50):
        fc.mark_seen(i)
    assert len(fc._seen_ids) == CATCHUP_SEEN_MAX  # 有界容量(长驻进程不泄漏)
    # 远窗 id 已被 FIFO 淘汰 → 可重新登记;补拉重叠窗口(100)<< 容量(4096),
    # 淘汰的 id 实际不会再出现在补拉页里,不构成重复派发
    assert fc.mark_seen(1) is True
    assert fc.mark_seen(CATCHUP_SEEN_MAX + 49) is False  # 最近 id 仍在


# ---------- _on_fill_ws 去重(WS 直播流 × 补拉流交叠) ----------


def _dispatch_loop(catchup) -> tuple[RunnerEventLoop, list]:
    loop = RunnerEventLoop()
    loop._symbol = "BTC/USDT"
    loop._market_type = "SPOT"
    loop._account_id = 7
    loop._ctx = MagicMock()
    seen: list = []
    loop._on_fill = lambda ev, ctx: seen.append(ev)
    loop._fill_catchup = catchup
    return loop, seen


def test_on_fill_ws_dedup_by_fill_id_and_decimal_amounts():
    loop, seen = _dispatch_loop(FillCatchup(FakeClient(lambda p, q: {})))

    asyncio.run(loop._on_fill_ws(_row(11)))
    asyncio.run(loop._on_fill_ws(_row(11)))  # 同 fillId 双路到达 → 只派发一次
    assert len(seen) == 1
    ev = seen[0]
    assert isinstance(ev, FillEvent)
    assert ev.price == D("42150.50")  # REST decimal string → Decimal(金额红线,不经 float)
    assert ev.qty == D("0.1") and ev.fee == D("0.4")
    assert ev.order_id == 5 and ev.side == "buy"
    assert ev.filled_at == "2026-07-04T12:00:05Z"

    # 缺 fillId(旧后端 WS 载荷偏斜)→ 跳过去重照常派发:缺去重键不等于该丢事件
    no_id = {k: v for k, v in _row(12).items() if k != "fillId"}
    asyncio.run(loop._on_fill_ws(no_id))
    asyncio.run(loop._on_fill_ws(no_id))
    assert len(seen) == 3


def test_on_fill_ws_without_catchup_keeps_legacy_no_dedup():
    # 未启用补拉(fill_catchup=None)→ 不引入去重语义(存量 WS 行为逐字保留)
    loop, seen = _dispatch_loop(None)
    asyncio.run(loop._on_fill_ws(_row(11)))
    asyncio.run(loop._on_fill_ws(_row(11)))
    assert len(seen) == 2


def test_catchup_row_respects_symbol_and_market_filters():
    # 补拉行走同一派发链路:账户内其他 symbol/市场类型的行照常被过滤(服务端只收口账户)
    loop, seen = _dispatch_loop(FillCatchup(FakeClient(lambda p, q: {})))
    asyncio.run(loop._on_fill_ws(_row(21, symbol="ETH/USDT")))
    asyncio.run(loop._on_fill_ws(_row(22, market_type="PERP")))
    assert seen == []


def test_filtered_row_does_not_claim_dedup_id():
    """被过滤行不认领去重键(认领在全部过滤通过之后):user 级直播流混有同用户其他
    账户/标的高频成交,入口登记会冲刷有界去重集,把本 runner 已派发 id 挤出窗口 →
    重叠回拉二次派发(exactly-once 破坏)。"""
    catchup = FillCatchup(FakeClient(lambda p, q: {}))
    loop, seen = _dispatch_loop(catchup)

    asyncio.run(loop._on_fill_ws(_row(31, symbol="ETH/USDT")))  # 错 symbol → 过滤丢弃
    assert seen == []
    assert 31 not in catchup._seen_ids  # id 未被认领(被过滤行不占用去重集;不消费式检查)

    asyncio.run(loop._on_fill_ws(_row(31)))  # 同 id 正确 symbol → 照常派发
    assert len(seen) == 1
    assert 31 in catchup._seen_ids  # 派发即认领
    assert catchup.mark_seen(31) is False  # 二次到达被去重拦下


# ---------- _catchup_drain 翻页与重叠 ----------


def test_catchup_drain_pagination_with_overlap():
    loop = RunnerEventLoop()
    afters: list[int] = []
    state = {"n": 0}
    full = [_row(i) for i in range(CATCHUP_PAGE_LIMIT)]

    def handler(path, params):
        afters.append(params["afterId"])
        state["n"] += 1
        if state["n"] == 1:
            return {"fills": full, "cursor": 500}
        return {"fills": [_row(1000)], "cursor": 1000}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    rows, cursor = asyncio.run(loop._catchup_drain(300, 0))

    assert afters == [200, 500]  # 首页 afterId = cursor - overlap(回拉兜"晚提交小 id"洞);翻页续页游标
    assert len(rows) == CATCHUP_PAGE_LIMIT + 1
    assert cursor == 1000


def test_catchup_drain_overlap_never_crosses_seed_floor():
    # floor 钳制:播种后首轮 cursor==floor,overlap 不回拉到 0(否则回放启动前历史)
    loop = RunnerEventLoop()
    afters: list[int] = []

    def handler(path, params):
        afters.append(params["afterId"])
        return {"fills": [], "cursor": params["afterId"]}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    asyncio.run(loop._catchup_drain(100, 100))
    assert afters == [100]


def test_catchup_drain_stalled_cursor_breaks_defensively():
    loop = RunnerEventLoop()
    calls = []

    def handler(path, params):
        calls.append(params["afterId"])
        # 病态响应:满页但 cursor 不前进(空页回显语义被违反)→ 防死循环退出
        return {"fills": [_row(i) for i in range(CATCHUP_PAGE_LIMIT)], "cursor": params["afterId"]}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    rows, cursor = asyncio.run(loop._catchup_drain(100, 0))

    assert len(calls) == 1  # 只拉一页即退出(不死循环)
    assert cursor == 100  # 游标不前进


# ---------- _fill_catchup_loop 集成(播种 → 拉取 → 派发 → exactly-once) ----------


def _drive_loop(loop: RunnerEventLoop, *, ticks: float = 0.01, until=None, max_wait: float = 2.0):
    """跑补拉循环直到 until() 为真或超时,取消任务收尾(测试用驱动器)。"""

    async def drive():
        task = asyncio.create_task(loop._fill_catchup_loop())
        waited = 0.0
        try:
            while waited < max_wait:
                await asyncio.sleep(ticks)
                waited += ticks
                if until is not None and until():
                    break
        finally:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    asyncio.run(drive())


def test_fill_catchup_loop_seeds_pulls_dispatches_exactly_once(monkeypatch):
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    loop, seen = _dispatch_loop(None)
    fetch_count = {"n": 0}

    def handler(path, params):
        if params is None:  # 播种
            return {"fills": [], "cursor": 5}
        fetch_count["n"] += 1
        # 每轮都返回同一行(重叠窗口会反复拉到它)→ 去重后必须只派发一次
        return {"fills": [_row(6)], "cursor": 6}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    _drive_loop(loop, until=lambda: fetch_count["n"] >= 3)

    assert len(seen) == 1 and isinstance(seen[0], FillEvent)  # exactly-once
    assert seen[0].price == D("42150.50")
    assert fetch_count["n"] >= 2  # 确有多轮 tick(不是一次性偶然)


def test_catchup_never_replays_pre_seed_history(monkeypatch):
    """P0 回归:overlap 回拉不得穿越播种 floor——账户已有历史成交 1..100、播种游标=100 时,
    首轮 drain 若回拉到 afterId=0 会把全部历史回放成 on_fill("重启不回放"契约系统性违约;
    历史 filled_at 可为数月前,而 on_fill 的文档用途含触发下单,LIVE 下是真实钱路径)。"""
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    loop, seen = _dispatch_loop(None)

    def marked(fill_id: int) -> dict:
        row = _row(fill_id)
        row["price"] = str(fill_id)  # price 作 id 标记(Decimal 直进 FillEvent.price)
        return row

    history = [marked(i) for i in range(1, 101)]  # 播种前历史成交(全部早过安全边界,可见)
    fresh = marked(101)  # 播种后新成交
    afters: list[int] = []

    def handler(path, params):
        if params is None:
            return {"fills": [], "cursor": 100}  # 播种 = 历史尾部
        after = params["afterId"]
        afters.append(after)
        rows = [r for r in history + [fresh] if r["fillId"] > after]
        return {"fills": rows[:CATCHUP_PAGE_LIMIT], "cursor": rows[-1]["fillId"] if rows else after}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    _drive_loop(loop, until=lambda: len(seen) >= 1)

    assert afters and min(afters) >= 100  # 任何一轮拉取都不越 floor
    assert len(seen) == 1 and seen[0].price == D("101")  # 只派发播种后新成交,历史零回放


def test_live_ws_then_catchup_same_fill_id_dispatched_once():
    """混流 exactly-once 核心叙事:直播 WS 先到已派发 fillId=X,补拉行同 X 后到 → 去重跳过。"""
    loop, seen = _dispatch_loop(FillCatchup(FakeClient(lambda p, q: {})))

    asyncio.run(loop._on_fill_ws(_row(41)))  # 直播流
    assert len(seen) == 1
    asyncio.run(loop._on_fill_ws(_row(41)))  # 补拉流重见同一成交
    assert len(seen) == 1


def test_malformed_live_payload_does_not_claim_dedup_id():
    """认领在 convert 成功之后:畸形直播载荷(缺 fee → convert 返 None)不吞 fillId——
    补拉行走 REST 独立序列化可能完好,必须仍能派发(否则 exactly-once 退化 at-most-zero)。"""
    loop, seen = _dispatch_loop(FillCatchup(FakeClient(lambda p, q: {})))

    bad = _row(51)
    del bad["fee"]
    asyncio.run(loop._on_fill_ws(bad))  # 直播畸形 → 丢弃且不认领
    assert seen == []
    asyncio.run(loop._on_fill_ws(_row(51)))  # 同 id 补拉行完好 → 照常派发
    assert len(seen) == 1


def test_catchup_dispatch_isolates_poison_row_and_cursor_advances(monkeypatch, capsys):
    """单行派发异常隔离:毒行不得杀死补拉循环、不得阻塞同页后续行、不得阻止游标推进——
    毒行卡页会让补拉通道永久停摆。布局 seed=1000/行 1499+1500 让推进量可观测:
    下轮 afterId=1400(=1500-overlap);若游标停滞则仍是 1000(删掉行级 try 外的
    cursor 推进本测试会红)。"""
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    loop, seen = _dispatch_loop(None)
    afters: list[int] = []
    poison, ok_row = _row(1499), _row(1500)
    poison["price"], ok_row["price"] = "1499", "1500"

    def handler(path, params):
        if params is None:
            return {"fills": [], "cursor": 1000}
        afters.append(params["afterId"])
        if params["afterId"] <= 1000:
            return {"fills": [poison, ok_row], "cursor": 1500}
        return {"fills": [], "cursor": params["afterId"]}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    real_dispatch = loop._on_fill_ws

    async def flaky(payload):
        if payload.get("fillId") == 1499:
            raise ValueError("contract drift")
        return await real_dispatch(payload)

    loop._on_fill_ws = flaky
    _drive_loop(loop, until=lambda: len(afters) >= 2)

    assert len(seen) == 1 and seen[0].price == D("1500")  # 毒行不阻塞同页后续行
    assert "catchup dispatch failed for fillId 1499" in capsys.readouterr().err
    assert afters[1] == 1400  # 游标推进到 1500(毒行不阻塞推进)


def test_stall_recovery_reseeds_instead_of_duplicate_storm(monkeypatch, capsys):
    """P1 回归(锁步重复风暴):补拉游标停摆(REST 发散故障、WS 直播照常)且停摆窗直播
    认领 ≥ 阈值时,恢复轮对全窗口的升序重扫与 seen 集 FIFO 淘汰**锁步**——每次"首次
    认领"恰淘汰尚未扫到的直播前沿 id,抑制率归零、整窗重复派发(仿真 5000/5000)。
    防护 = 放弃窗口重播种(cursor/floor 同抬,窗口缺口归对账契约),零重复 + WARN 出声。"""
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    monkeypatch.setattr(el, "CATCHUP_STALL_RESEED_MIN", 8)  # 阈值缩小,锁步机理等比成立
    loop, seen = _dispatch_loop(None)
    afters: list[int] = []

    def handler(path, params):
        if params is None:
            return {"fills": [], "cursor": 1000}
        afters.append(params["afterId"])
        if len(afters) == 1:
            raise RuntimeError("fills-since 502")  # 停摆轮:REST 故障,直播照常
        after = params["afterId"]
        rows = [_row(i) for i in range(1001, 1012) if i > after]  # 停摆窗口 11 行
        return {"fills": rows, "cursor": rows[-1]["fillId"] if rows else after}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    # 停摆前直播已认领 10 笔(≥ 阈值 8):id 1001..1010 与停摆窗口重叠
    for i in range(1001, 1011):
        asyncio.run(loop._on_fill_ws(_row(i)))
    assert loop._catchup_claims == 10 and len(seen) == 10

    _drive_loop(loop, until=lambda: len(afters) >= 3)

    # 零重复:恢复轮的 11 行补拉一行都没进回调(窗口整体放弃,含真漏的 1011——
    # 已声明权衡:停摆窗归对账契约)
    assert len(seen) == 10
    err = capsys.readouterr().err
    assert "reseeding" in err and "dispatched live" in err
    # floor 抬升:重播种后 afterId=1011(若 floor 仍是 1000,overlap 会把 afterId 压回 1000 重入窗口)
    assert afters[-1] == 1011


def test_ws_down_large_backfill_dispatches_without_reseed(monkeypatch):
    """补拉核心价值场景不得被停摆防护误杀:WS 断很久、REST 健康(直播认领=0)——大窗口
    (行数 ≥ 阈值)照常全量补发;阈值条件必须含直播认领数,纯行数阈值会误杀本场景。"""
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    monkeypatch.setattr(el, "CATCHUP_STALL_RESEED_MIN", 8)
    loop, seen = _dispatch_loop(None)

    def handler(path, params):
        if params is None:
            return {"fills": [], "cursor": 0}
        after = params["afterId"]
        rows = [_row(i) for i in range(1, 31) if i > after]  # 30 行 >> 阈值 8
        return {"fills": rows, "cursor": rows[-1]["fillId"] if rows else after}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    _drive_loop(loop, until=lambda: len(seen) >= 30)

    assert len(seen) == 30  # 全窗补发,零重播种(drain 时直播认领=0)


def test_claims_reset_each_successful_round_no_false_reseed(monkeypatch, capsys):
    """轮末清零判别锁:删掉 `self._catchup_claims = 0` → 认领终身累计,连续三轮各 5 笔
    新行,第三轮累计 10 ≥ 阈值 8 且有行 → 误触发重播种(第三轮 5 笔被整窗放弃)——
    生产语义:补拉永久退化为整窗放弃 + WARN 刷屏。清零正常时 15 笔全量补发、零 WARN。"""
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    monkeypatch.setattr(el, "CATCHUP_STALL_RESEED_MIN", 8)
    loop, seen = _dispatch_loop(None)
    state = {"round": 0}

    def handler(path, params):
        if params is None:
            return {"fills": [], "cursor": 0}
        after = params["afterId"]
        if after <= 0 and state["round"] == 0:
            state["round"] = 1
            return {"fills": [_row(i) for i in range(1, 6)], "cursor": 5}
        if after <= 5 and state["round"] == 1:
            state["round"] = 2
            return {"fills": [_row(i) for i in range(6, 11)], "cursor": 10}
        if after <= 10 and state["round"] == 2:
            state["round"] = 3
            return {"fills": [_row(i) for i in range(11, 16)], "cursor": 15}
        return {"fills": [], "cursor": after}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    _drive_loop(loop, until=lambda: len(seen) >= 15)

    assert len(seen) == 15  # 三轮全量补发(每轮开始时 claims 已被上轮末清零,5 < 8 不误触发)
    assert "reseeding" not in capsys.readouterr().err  # 全程零误杀 WARN


def test_run_streams_wires_catchup_loop_only_when_enabled():
    """_run_streams 接线锁:fill_catchup+on_fill 齐备时补拉循环必须与 WS 主通道并行——
    删掉 gather 第二任务,既有测试全绿(它们直接驱动 _fill_catchup_loop),此处是唯一锁。"""
    loop = RunnerEventLoop()
    started: list[str] = []

    async def fake_catchup():
        started.append("catchup")

    class FakeStream:
        async def run(self):
            started.append("stream")

    loop._on_fill = lambda ev, ctx: None
    loop._fill_catchup = FillCatchup(FakeClient(lambda p, q: {}))
    loop._fill_catchup_loop = fake_catchup
    asyncio.run(loop._run_streams(FakeStream()))
    assert set(started) == {"stream", "catchup"}

    started.clear()
    loop._fill_catchup = None
    asyncio.run(loop._run_streams(FakeStream()))
    assert started == ["stream"]  # 未装配补拉 → 主通道行为与存量逐字一致


def test_fill_catchup_loop_seed_failure_never_pulls_with_zero_cursor(monkeypatch, capsys):
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    loop, seen = _dispatch_loop(None)
    calls: list = []

    def handler(path, params):
        calls.append(params)
        raise RuntimeError("java down")

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    _drive_loop(loop, until=lambda: len(calls) >= 3)

    # 播种持续失败:只重试播种,绝不带未播种游标分页拉取(游标 0 会回放账户全部成交历史)
    assert len(calls) >= 2 and all(p is None for p in calls)
    assert seen == []
    assert "fill catchup seed failed" in capsys.readouterr().err


def test_fill_catchup_loop_seed_recovers_on_retry(monkeypatch):
    monkeypatch.setattr(el, "CATCHUP_INTERVAL_S", 0.01)
    loop, seen = _dispatch_loop(None)
    state = {"seed_attempts": 0, "fetched": 0}

    def handler(path, params):
        if params is None:
            state["seed_attempts"] += 1
            if state["seed_attempts"] == 1:
                raise RuntimeError("java restarting")  # 首轮播种失败(部署/重启窗口)
            return {"fills": [], "cursor": 5}
        state["fetched"] += 1
        return {"fills": [_row(6)], "cursor": 6}

    loop._fill_catchup = FillCatchup(FakeClient(handler))
    _drive_loop(loop, until=lambda: len(seen) >= 1)

    assert state["seed_attempts"] >= 2  # 失败后重试播种
    assert len(seen) == 1  # 恢复后正常派发


# ---------- worker_server 装配门控 ----------


def test_run_runner_fill_catchup_gating(monkeypatch):
    """装配门控:①未定义 on_fill → None(无补拉语义);②定义 on_fill 但 bootstrap 无
    userId(旧后端偏斜,补拉端点必然同批缺失)→ None(事件通道整体不启用,避免每轮
    404 噪音);③on_fill + userId 齐 → 装配 FillCatchup。"""
    from kwikquant_worker import worker_server

    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    import kwikquant_worker.health_server as hs_mod

    class FakeHealth:
        def __init__(self, *a, **kw):
            pass

        def start(self):
            pass

        def stop(self):
            pass

    monkeypatch.setattr(hs_mod, "HealthServer", FakeHealth)
    monkeypatch.setattr(worker_server, "_prefill_history", lambda *a, **kw: None)

    run_calls = {}

    class FakeLoop:
        def __init__(self, *a, **kw):
            pass

        def run(self, on_bar, ctx, stream, **kw):
            run_calls["kwargs"] = kw

    monkeypatch.setattr(el, "RunnerEventLoop", FakeLoop)

    def run_with(cfg_overrides, source):
        cfg = {
            "strategyId": 5,
            "strategyName": "s",
            "sourceCode": source,
            "symbol": "BTC/USDT",
            "exchange": "OKX",
            "marketType": "SPOT",
            "intervalValue": "1h",
            "parameters": "{}",
            "apiBaseUrl": "http://localhost:9999",
            **cfg_overrides,
        }
        # main() 每次跑完 _clear_worker_secrets 会清掉 env 里的 token(防 /proc 窃取),
        # 多轮调用须每轮重设
        monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
        monkeypatch.setattr(worker_server, "_fetch_bootstrap", lambda token, base: cfg)
        assert worker_server.main(["--mode", "runner"]) == 0
        return run_calls["kwargs"]

    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://localhost:9999")

    on_fill_src = "def on_bar(bar, ctx):\n    pass\ndef on_fill(f, ctx):\n    pass\n"
    plain_src = "def on_bar(bar, ctx):\n    pass\n"

    kw = run_with({"userId": 42, "accountId": 7}, plain_src)
    assert kw["fill_catchup"] is None  # ① 未定义 on_fill

    kw = run_with({}, on_fill_src)
    assert kw["fill_catchup"] is None and kw["user_id"] is None  # ② 旧 bootstrap

    kw = run_with({"userId": 42, "accountId": 7}, on_fill_src)
    assert isinstance(kw["fill_catchup"], FillCatchup)  # ③ 装配
