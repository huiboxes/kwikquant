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
    worker_server._apply_resource_limits()
    assert captured[resource.RLIMIT_CPU] == (60, 60)
    assert captured[resource.RLIMIT_AS] == (1073741824, 1073741824)


def test_main_missing_token_returns_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
    monkeypatch.delenv("WORKER_SERVICE_TOKEN", raising=False)
    monkeypatch.setenv("TASK_CONFIG_JSON", "{}")
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_main_missing_config_returns_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_main_malformed_config_returns_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.setenv("TASK_CONFIG_JSON", "not-json")
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_main_removes_worker_secrets_before_user_code(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
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
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)

    started = {"count": 0}
    stopped = {"count": 0}

    class FakeHealth:
        def __init__(self, *a, **kw):
            self.status_provider = kw.get("status_provider")

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
        def run(self, on_bar, ctx, stream, **kw):
            run_calls["on_bar"] = on_bar
            run_calls["kwargs"] = kw

    monkeypatch.setattr(el_mod, "RunnerEventLoop", FakeLoop)

    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.setenv(
        "TASK_CONFIG_JSON",
        json.dumps(
            {
                "strategyId": 5,
                "symbol": "BTC/USDT",
                "exchange": "OKX",
                "marketType": "SPOT",
                "intervalValue": "1h",
                "sourceCode": "def on_bar(bar, ctx):\n    pass",
            }
        ),
    )
    # 无 server:subscribe/unsubscribe REST 失败由 _run_runner 容错吞掉(不影响 runner 启动)
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://localhost:9999")
    rc = worker_server.main(["--mode", "runner"])
    assert rc == 0
    assert started["count"] == 1 and stopped["count"] == 1
    assert callable(run_calls["on_bar"])
    assert run_calls["kwargs"]["exchange"] == "OKX"
    assert run_calls["kwargs"]["symbol"] == "BTC/USDT"
    assert run_calls["kwargs"]["interval"] == "1h"



def test_run_backtest_stdout_prints_section8(monkeypatch, capsys):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)

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
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
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
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
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
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
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
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
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


def test_extract_warmup_bars_default_cap_invalid():
    """WARMUP_BARS:缺省 0(不回填);超 999 封顶;非法值按 0。"""
    base = "def on_bar(bar, ctx):\n    return\n"
    assert worker_server._extract_warmup_bars(worker_server._load_strategy_module(base)) == 0
    m300 = worker_server._load_strategy_module("WARMUP_BARS = 300\n" + base)
    assert worker_server._extract_warmup_bars(m300) == 300
    m_over = worker_server._load_strategy_module("WARMUP_BARS = 5000\n" + base)
    assert worker_server._extract_warmup_bars(m_over) == 999
    m_bad = worker_server._load_strategy_module("WARMUP_BARS = 'abc'\n" + base)
    assert worker_server._extract_warmup_bars(m_bad) == 0


def _kline(ts: str, close: str) -> dict:
    return {"openTime": ts, "open": close, "high": close, "low": close, "close": close, "volume": "1"}


def test_warmup_runner_history_fills_sorted_drops_last():
    """回填:按 openTime 升序(DB DESC/CCXT ASC 混合源)+ 丢最后一根(可能活 bar);只灌 ctx 不触发 on_bar。"""
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    # REST 混合序(DESC 输入)验证排序
    client.data.klines_recent.return_value = [
        _kline("2026-08-16T02:00:00Z", "3"),
        _kline("2026-08-16T00:00:00Z", "1"),
        _kline("2026-08-16T01:00:00Z", "2"),
    ]
    module = worker_server._load_strategy_module("WARMUP_BARS = 2\ndef on_bar(bar, ctx):\n    return\n")
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    filled = worker_server._warmup_runner_history(
        ctx, client, module, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="15m"
    )

    assert filled == 2
    assert ctx.history("close", 10) == [1.0, 2.0]  # 升序;"3"(最新,可能活 bar)被丢
    assert client.data.klines_recent.call_args.args[4] == 3  # limit = n+1(留 1 根丢尾)


def test_warmup_runner_history_zero_declared_no_fetch():
    """WARMUP_BARS=0/缺省:不拉 REST,返 0。"""
    client = MagicMock()
    module = worker_server._load_strategy_module("def on_bar(bar, ctx):\n    return\n")
    filled = worker_server._warmup_runner_history(
        client=client, ctx=MagicMock(), module=module,
        exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="15m",
    )
    assert filled == 0
    client.data.klines_recent.assert_not_called()


def test_warmup_runner_history_fetch_failure_tolerated():
    """warmup 拉取失败记 stderr 返 0,不阻断 runner 启动(策略 history 长度守卫兜底)。"""
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    client.data.klines_recent.side_effect = RuntimeError("502 exchange down")
    module = worker_server._load_strategy_module("WARMUP_BARS = 100\ndef on_bar(bar, ctx):\n    return\n")
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    filled = worker_server._warmup_runner_history(
        ctx, client, module, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="15m"
    )

    assert filled == 0
    assert ctx.history("close", 10) == []
