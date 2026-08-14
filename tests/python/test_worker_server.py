"""worker_server.py — CLI 入口 + rlimit 首行 + mode 派发。"""

from __future__ import annotations

import ast
import json
import os
import re
from decimal import Decimal
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from kwikquant_worker import worker_server


ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "kwikquant_worker" / "worker_server.py"


def test_main_first_executable_stmt_is_resource_setrlimit_call():
    """红线:main() 首个**可执行语句**必须是 _apply_resource_limits()。

    允许开头 docstring(非可执行),但 docstring 之后的**首**条 stmt 必须调 rlimit,
    不允许夹任何其它 import/赋值/日志设置。
    """
    tree = ast.parse(SRC.read_text())
    main_fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "main")
    stmts = main_fn.body
    idx = 0
    # 跳过 docstring(第一 stmt 是 Expr(Constant(str)))
    if (
        isinstance(stmts[0], ast.Expr)
        and isinstance(stmts[0].value, ast.Constant)
        and isinstance(stmts[0].value.value, str)
    ):
        idx = 1
    first_exec = stmts[idx]
    assert isinstance(first_exec, ast.Expr) and isinstance(first_exec.value, ast.Call), (
        "main() docstring 之后的首个 stmt 必须是可执行 Call,不能是赋值/import/其他"
    )
    called = ast.unparse(first_exec.value.func)
    assert called == "_apply_resource_limits", (
        f"main() 首个可执行 stmt 应为 _apply_resource_limits(),实际 {called}"
    )


def test_source_no_import_or_assign_between_def_and_rlimit():
    """再一次防御:def main 到 rlimit 之间除 docstring 外不能夹任何 import/赋值。"""
    src = SRC.read_text()
    m = re.search(r"def main\([^\n]*\)[^\n]*:\n", src)
    assert m is not None
    rest = src[m.end():]
    lines = rest.split("\n")
    # 跳过 docstring 段(若有)
    i = 0
    if lines[i].lstrip().startswith('"""'):
        # 单行 docstring 或多行 docstring
        if lines[i].count('"""') >= 2:
            i += 1
        else:
            i += 1
            while i < len(lines) and '"""' not in lines[i]:
                i += 1
            i += 1  # 跳过结尾 """ 那行
    # 忽略空行
    while i < len(lines) and (not lines[i].strip() or lines[i].strip().startswith("#")):
        i += 1
    assert "_apply_resource_limits" in lines[i], (
        f"main() docstring 之后首个非空行应调 _apply_resource_limits,实际 {lines[i]!r}"
    )


def test_apply_resource_limits_sets_cpu_and_as(monkeypatch):
    calls = []

    def fake_setrlimit(res, tup):
        calls.append((res, tup))

    import resource

    monkeypatch.setattr(resource, "setrlimit", fake_setrlimit)
    monkeypatch.delenv("KWIKQUANT_RLIMIT_CPU_SEC", raising=False)
    monkeypatch.delenv("KWIKQUANT_RLIMIT_AS_BYTES", raising=False)
    worker_server._apply_resource_limits()
    resources = {c[0] for c in calls}
    assert resource.RLIMIT_CPU in resources
    # RLIMIT_AS 可能被 macOS 静默忽略,但 fake 不会抛,所以两次都调
    assert resource.RLIMIT_AS in resources


def test_apply_resource_limits_reads_env_overrides(monkeypatch):
    captured = {}

    def fake(res, tup):
        captured[res] = tup

    import resource

    monkeypatch.setattr(resource, "setrlimit", fake)
    monkeypatch.setenv("KWIKQUANT_RLIMIT_CPU_SEC", "60")
    monkeypatch.setenv("KWIKQUANT_RLIMIT_AS_BYTES", "1073741824")
    worker_server._apply_resource_limits(["--mode", "backtest"])
    assert captured[resource.RLIMIT_CPU] == (60, 60)
    assert captured[resource.RLIMIT_AS] == (1073741824, 1073741824)


@pytest.mark.parametrize("argv", [["--mode", "runner"], ["--mode=runner"]])
def test_apply_resource_limits_runner_mode_skips_cpu(monkeypatch, argv):
    """红线:runner 是长驻进程,RLIMIT_CPU(累计 CPU 时间)达限会 SIGKILL 丢策略状态 → 不设。"""
    calls = []

    def fake_setrlimit(res, tup):
        calls.append((res, tup))

    import resource

    monkeypatch.setattr(resource, "setrlimit", fake_setrlimit)
    monkeypatch.delenv("KWIKQUANT_RLIMIT_CPU_SEC", raising=False)
    monkeypatch.delenv("KWIKQUANT_RLIMIT_AS_BYTES", raising=False)
    worker_server._apply_resource_limits(argv)
    resources = {c[0] for c in calls}
    assert resource.RLIMIT_CPU not in resources
    assert resource.RLIMIT_AS in resources


def test_main_missing_token_returns_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.delenv("WORKER_SERVICE_TOKEN", raising=False)
    monkeypatch.setenv("TASK_CONFIG_JSON", "{}")
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_main_missing_config_returns_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_main_malformed_config_returns_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.setenv("TASK_CONFIG_JSON", "not-json")
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_main_reads_config_from_stdin_when_env_missing(monkeypatch):
    """DockerBacktestRunner 经 stdin(docker run -i)下发配置:env 缺失时读 stdin。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    import io

    monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"taskId": 1})))
    observed = {}

    def fake_run(cfg, service_token, api_base):
        observed["cfg"] = cfg
        return 0

    monkeypatch.setattr(worker_server, "_run_backtest", fake_run)
    assert worker_server.main(["--mode", "backtest"]) == 0
    assert observed["cfg"]["taskId"] == 1


def test_main_env_config_takes_precedence_over_stdin(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps({"taskId": 7}))
    import io

    monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"taskId": 9})))
    observed = {}

    def fake_run(cfg, service_token, api_base):
        observed["cfg"] = cfg
        return 0

    monkeypatch.setattr(worker_server, "_run_backtest", fake_run)
    assert worker_server.main(["--mode", "backtest"]) == 0
    assert observed["cfg"]["taskId"] == 7


def test_main_removes_worker_secrets_before_user_code(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "worker-secret")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps({"sourceCode": "unused"}))
    monkeypatch.setenv("WORKER_PG_READONLY_DSN", "password=db-secret")
    observed = {}

    def fake_run(cfg, service_token, api_base):
        observed.update(os.environ)
        assert service_token == "worker-secret"
        return 0

    monkeypatch.setattr(worker_server, "_run_backtest", fake_run)

    assert worker_server.main(["--mode", "backtest"]) == 0
    assert "WORKER_SERVICE_TOKEN" not in observed
    assert "TASK_CONFIG_JSON" not in observed
    assert "WORKER_PG_READONLY_DSN" not in observed


def test_main_runner_mode_starts_health_and_runs_event_loop(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)

    started = {"count": 0}
    stopped = {"count": 0}
    wired = {}

    class FakeHealth:
        def __init__(self, *a, **kw):
            self.status_provider = kw.get("status_provider")
            wired["status_provider"] = kw.get("status_provider")

        def start(self):
            started["count"] += 1

        def stop(self):
            stopped["count"] += 1

    # patch import 到 worker_server 内部的 HealthServer
    import kwikquant_worker.health_server as hs_mod
    monkeypatch.setattr(hs_mod, "HealthServer", FakeHealth)

    # mock RunnerEventLoop.run noop(避免 asyncio.run StreamClient.run 连真实 WS 长驻)
    import kwikquant_worker.event_loop as el_mod
    run_calls = {}

    class FakeLoop:
        def __init__(self, *a, **kw):
            run_calls["init_kwargs"] = kw

        def run(self, on_bar, ctx, stream, **kw):
            run_calls["on_bar"] = on_bar
            run_calls["kwargs"] = kw

    monkeypatch.setattr(el_mod, "RunnerEventLoop", FakeLoop)
    # 隔离 runner 历史 bar 预填(单独测 _prefill_history,此处不打真实 HTTP 到 :9999)
    monkeypatch.setattr(worker_server, "_prefill_history", lambda *a, **kw: None)

    # runner 走拉取式 bootstrap(③):main 调 _fetch_bootstrap 拉 cfg,不读 env TASK_CONFIG_JSON。
    # mock _fetch_bootstrap 返回 cfg(含 sourceCode),替代真 HTTP 到 :9999。
    bootstrap_cfg = {
        "strategyId": 5,
        "strategyName": "test-strat",
        "sourceCode": "def on_bar(bar, ctx):\n    pass",
        "symbol": "BTC/USDT",
        "exchange": "OKX",
        "marketType": "SPOT",
        "intervalValue": "1h",
        "parameters": "{}",
        "apiBaseUrl": "http://localhost:9999",
    }
    monkeypatch.setattr(worker_server, "_fetch_bootstrap", lambda token, base: bootstrap_cfg)

    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)  # runner 不读 env config,走 bootstrap
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://localhost:9999")
    rc = worker_server.main(["--mode", "runner"])
    assert rc == 0
    assert started["count"] == 1 and stopped["count"] == 1
    assert callable(run_calls["on_bar"])
    assert run_calls["kwargs"]["exchange"] == "OKX"
    assert run_calls["kwargs"]["symbol"] == "BTC/USDT"
    assert run_calls["kwargs"]["interval"] == "1h"
    # HealthSignals wire:_run_runner 构造 signals 传给 HealthServer(snapshot)+ RunnerEventLoop
    assert run_calls["init_kwargs"]["health_signals"] is not None
    assert callable(wired["status_provider"])



def _fake_bootstrap_client(get_return=None, get_side_effect=None):
    """构造 mock kwikquant.client.Client/Auth(供 _fetch_bootstrap 测试,避开 httpx transport 注入)。

    每次调用生成独立的 captured dict + FakeClient 类(闭包),测试间互不干扰。
    """
    import kwikquant.client as client_mod

    class FakeAuth:
        @staticmethod
        def service_token(token):
            return object()

    captured = {}

    class FakeClient:
        def __init__(self, base_url, auth):
            captured["base_url"] = base_url

        def get(self, path, *, params=None, timeout=None):
            captured["path"] = path
            if get_side_effect is not None:
                raise get_side_effect
            return get_return

        def close(self):
            captured["closed"] = True

    return client_mod, FakeAuth, FakeClient, captured


def test_main_runner_bootstrap_failure_returns_1(monkeypatch, capsys):
    """runner bootstrap 拉取失败(401/404/网络)→ main catch → exit 1(stderr 记 bootstrap failed)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setattr(
        worker_server, "_fetch_bootstrap", lambda *a: (_ for _ in ()).throw(RuntimeError("401 token"))
    )
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    assert worker_server.main(["--mode", "runner"]) == 1
    assert "bootstrap failed" in capsys.readouterr().err


def test_fetch_bootstrap_returns_config_dict(monkeypatch):
    """_fetch_bootstrap:RUNNER service token 鉴权 GET /api/v1/worker/bootstrap,
    client.get 已 unwrap envelope → 返 cfg dict(含 sourceCode);client.close 防泄漏。"""
    client_mod, FakeAuth, FakeClient, captured = _fake_bootstrap_client(get_return={
        "strategyId": 5, "strategyName": "s",
        "sourceCode": "def on_bar(bar, ctx): pass",
        "symbol": "BTC/USDT", "exchange": "OKX", "marketType": "SPOT",
        "intervalValue": "1h", "parameters": "{}", "apiBaseUrl": "http://k",
    })
    monkeypatch.setattr(client_mod, "Auth", FakeAuth)
    monkeypatch.setattr(client_mod, "Client", FakeClient)

    cfg = worker_server._fetch_bootstrap("tok-abc", "http://kwikquant-app:8080")

    assert captured["base_url"] == "http://kwikquant-app:8080"
    assert captured["path"] == "/api/v1/worker/bootstrap"
    assert captured["closed"] is True
    assert cfg["strategyId"] == 5
    assert "on_bar" in cfg["sourceCode"]


def test_fetch_bootstrap_failure_propagates(monkeypatch):
    """bootstrap 拉取失败(401/404/网络)→ _fetch_bootstrap 抛 → main catch → exit 1。"""
    client_mod, FakeAuth, FakeClient, _ = _fake_bootstrap_client(
        get_side_effect=RuntimeError("401 token invalid")
    )
    monkeypatch.setattr(client_mod, "Auth", FakeAuth)
    monkeypatch.setattr(client_mod, "Client", FakeClient)

    with pytest.raises(RuntimeError, match="401 token invalid"):
        worker_server._fetch_bootstrap("bad-tok", "http://k")


def test_fetch_bootstrap_non_dict_raises(monkeypatch):
    """bootstrap 返非 dict(envelope 异常)→ _fetch_bootstrap 抛 RuntimeError(missing strategyId)。"""
    client_mod, FakeAuth, FakeClient, _ = _fake_bootstrap_client(get_return="not a dict")
    monkeypatch.setattr(client_mod, "Auth", FakeAuth)
    monkeypatch.setattr(client_mod, "Client", FakeClient)

    with pytest.raises(RuntimeError, match="missing strategyId"):
        worker_server._fetch_bootstrap("tok", "http://k")


def test_fetch_bootstrap_missing_strategy_id_raises(monkeypatch):
    """bootstrap 返 dict 但缺 strategyId(后端异常)→ 抛 RuntimeError(missing strategyId)。"""
    client_mod, FakeAuth, FakeClient, _ = _fake_bootstrap_client(get_return={"symbol": "BTC/USDT"})
    monkeypatch.setattr(client_mod, "Auth", FakeAuth)
    monkeypatch.setattr(client_mod, "Client", FakeClient)

    with pytest.raises(RuntimeError, match="missing strategyId"):
        worker_server._fetch_bootstrap("tok", "http://k")


def _prefill_kline(t: str, close: str = "1") -> dict:
    """REST /market/klines Kline record 形状(含 openTime,runner 预填经 _bar_from_kline 映射)。"""
    return {
        "openTime": t, "open": "1", "high": "2", "low": "0", "close": str(close), "volume": "10",
        "exchange": "OKX", "marketType": "SPOT", "symbol": "BTC/USDT", "interval": "1h",
    }


def test_prefill_history_fetches_and_drops_last_bar(monkeypatch):
    """ohlcv 返 N+1 根 → 丢末根(可能未关闭)→ ctx 填 N 根。末根 close=30 被丢,末根=20。"""
    monkeypatch.delenv("KWIKQUANT_RUNNER_PREFILL_BARS", raising=False)
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    client.data.ohlcv.return_value = [_prefill_kline("T1", 10), _prefill_kline("T2", 20), _prefill_kline("T3", 30)]
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    worker_server._prefill_history(
        client, ctx, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="1h"
    )

    assert ctx.history("close", 99) == [10.0, 20.0]  # T3(close 30)被丢
    assert ctx.history("close", 1) == [20.0]  # 末根 T2
    # limit = DEFAULT_PREFILL_BARS + 1;market_type 透传(后端 @RequestParam 必需)
    assert client.data.ohlcv.call_args.kwargs["limit"] == worker_server.DEFAULT_PREFILL_BARS + 1
    assert client.data.ohlcv.call_args.kwargs["market_type"] == "SPOT"
    assert client.data.ohlcv.call_args.kwargs["symbol"] == "BTC/USDT"


def test_prefill_history_failure_does_not_raise(monkeypatch, capsys):
    """ohlcv 抛(网络/4xx)→ _prefill_history 吞掉不抛,ctx 保持 warmup 空(WS 路径照常)。"""
    monkeypatch.delenv("KWIKQUANT_RUNNER_PREFILL_BARS", raising=False)
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    client.data.ohlcv.side_effect = RuntimeError("network down")
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    worker_server._prefill_history(
        client, ctx, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="1h"
    )

    assert ctx.history("close", 1) == []  # 未预填
    assert "prefill ohlcv failed" in capsys.readouterr().err


def test_prefill_history_empty_result_no_crash(monkeypatch):
    """ohlcv 返 [](新 symbol 无历史)→ prefill_bars([]) → _index=-1,history 返 [](从头 warmup)。"""
    monkeypatch.delenv("KWIKQUANT_RUNNER_PREFILL_BARS", raising=False)
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    client.data.ohlcv.return_value = []
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    worker_server._prefill_history(
        client, ctx, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="1h"
    )

    assert ctx.history("close", 1) == []


def test_prefill_history_env_count_overrides_default(monkeypatch):
    """env KWIKQUANT_RUNNER_PREFILL_BARS=5 → ohlcv limit=6(5+1 丢末根),ctx 填 5 根。"""
    monkeypatch.setenv("KWIKQUANT_RUNNER_PREFILL_BARS", "5")
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    client.data.ohlcv.return_value = [_prefill_kline(f"T{i}", i) for i in range(6)]
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    worker_server._prefill_history(
        client, ctx, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="1h"
    )

    assert client.data.ohlcv.call_args.kwargs["limit"] == 6
    assert len(ctx.history("close", 99)) == 5  # 6 根丢末根 → 5


def test_prefill_history_zero_count_skips_fetch(monkeypatch):
    """env=0 → 关闭预填,不调 ohlcv(不产生 HTTP),ctx 保持 warmup 空。"""
    monkeypatch.setenv("KWIKQUANT_RUNNER_PREFILL_BARS", "0")
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    worker_server._prefill_history(
        client, ctx, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="1h"
    )

    client.data.ohlcv.assert_not_called()
    assert ctx.history("close", 1) == []


def test_run_backtest_stdout_prints_section8(monkeypatch, capsys):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)

    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "period": {"start": "", "end": ""}}

    from kwikquant_worker import event_loop as el

    observed = {}

    def fake_run(self, on_bar, ctx, klines):
        observed["reproducibility"] = self.reproducibility
        observed["params"] = self.params
        return section8

    monkeypatch.setattr(el.BacktestEventLoop, "run", fake_run)

    # data_loader 返非空(有数据,跑 event_loop);拉空已改 exit 2
    from kwikquant_worker import worker_server as ws
    monkeypatch.setattr(
        "kwikquant_worker.data_loader.load_klines",
        lambda *a, **kw: [{"timestamp": "t", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"}],
    )

    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbol": "BTC/USDT", "exchange": "BINANCE", "intervalValue": "1h",
        "startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-02T00:00:00Z",
        "parameters": "{}",
        "strategySource": "def on_bar(bar, ctx):\n    pass",
        "matchingConfig": {"marketSlippageBps": "5", "takerFeeRate": "0.002"},
    }
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://kw")

    rc = ws.main(["--mode", "backtest"])
    assert rc == 0
    out = capsys.readouterr().out.strip()
    assert json.loads(out) == section8
    snapshot = observed["reproducibility"]
    assert snapshot["strategyCodeHash"].startswith("sha256:")
    assert snapshot["data"]["version"].startswith("sha256:")
    assert snapshot["data"]["bars"] == 1
    assert snapshot["matching"]["marketSlippageBps"] == "5"
    assert snapshot["execution"]["orderFillTiming"] == "NEXT_BAR"
    assert observed["params"] == {}


def test_run_backtest_load_klines_failure_returns_1(monkeypatch, capsys):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setattr(
        "kwikquant_worker.data_loader.load_klines",
        lambda *a, **kw: (_ for _ in ()).throw(RuntimeError("pg down")),
    )
    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbol": "BTC/USDT", "exchange": "BINANCE", "intervalValue": "1h",
        "startTime": "s", "endTime": "e", "parameters": "{}",
    }
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_run_backtest_empty_klines_exits_2(monkeypatch, capsys):
    # 区间无历史数据 → exit 2(stderr NO_MARKET_DATA),Java Runner 抛
    # BacktestNoMarketDataException → markFailed 7304
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setattr(
        "kwikquant_worker.data_loader.load_klines",
        lambda *a, **kw: [],
    )
    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbol": "BTC/USDT", "exchange": "OKX", "marketType": "SPOT", "intervalValue": "1h",
        "startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-02T00:00:00Z",
        "parameters": "{}",
    }
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))

    rc = worker_server.main(["--mode", "backtest"])
    assert rc == 2, "拉空 → exit 2,Java markFailed 7304"
    err = capsys.readouterr().err
    assert "NO_MARKET_DATA" in err


def test_run_backtest_7303_exits_0(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    from kwikquant.errors import KqBacktestTaskNotRunning
    from kwikquant_worker import event_loop as el

    def _raise(*a, **kw):
        raise KqBacktestTaskNotRunning(409, 7303, "not running")

    monkeypatch.setattr(el.BacktestEventLoop, "run", _raise)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", lambda *a, **kw: [{"timestamp": "t", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"}])
    cfg = {"taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
           "symbol": "X", "exchange": "Y", "intervalValue": "1h",
           "startTime": "s", "endTime": "e", "parameters": "{}",
           "strategySource": "def on_bar(bar, ctx):\n    pass"}
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    assert worker_server.main(["--mode", "backtest"]) == 0


def test_run_backtest_7301_exits_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    from kwikquant.errors import KqAuthError
    from kwikquant_worker import event_loop as el

    def _raise(*a, **kw):
        raise KqAuthError(401, 7301, "expired")

    monkeypatch.setattr(el.BacktestEventLoop, "run", _raise)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", lambda *a, **kw: [{"timestamp": "t", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"}])
    cfg = {"taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
           "symbol": "X", "exchange": "Y", "intervalValue": "1h",
           "startTime": "s", "endTime": "e", "parameters": "{}",
           "strategySource": "def on_bar(bar, ctx):\n    pass"}
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_extract_initial_capital_defaults_100k():
    assert worker_server._extract_initial_capital({}) == Decimal("100000")


def test_extract_initial_capital_from_params():
    assert worker_server._extract_initial_capital({"initial_capital": "50000"}) == Decimal("50000")


def test_extract_initial_capital_malformed_returns_default():
    assert worker_server._extract_initial_capital({"initial_capital": "oops"}) == Decimal("100000")


def test_parse_parameters_dict_string_and_none():
    assert worker_server._parse_parameters(None) == {}
    assert worker_server._parse_parameters({"a": 1}) == {"a": 1}
    assert worker_server._parse_parameters('{"a":1}') == {"a": 1}
    assert worker_server._parse_parameters("bogus") == {}


def test_instantiate_strategy_no_source_raises():
    with pytest.raises(ValueError, match="策略源码为空"):
        worker_server._instantiate_strategy(None)


def test_instantiate_strategy_source_with_on_bar_returns_callable():
    source = "def on_bar(bar, ctx):\n    pass\n"
    on_bar = worker_server._instantiate_strategy(source)
    assert callable(on_bar)


def test_instantiate_strategy_source_without_on_bar_raises():
    with pytest.raises(ValueError, match="未定义顶层 def on_bar"):
        worker_server._instantiate_strategy("x = 1\n")
