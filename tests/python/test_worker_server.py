"""worker_server.py — CLI 入口 + rlimit 首行 + mode 派发(Wave 8 §3.3/§3.5)。"""

from __future__ import annotations

import ast
import json
import re
from decimal import Decimal
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from kwikquant_worker import worker_server


ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "kwikquant_worker" / "worker_server.py"


def test_main_first_executable_stmt_is_resource_setrlimit_call():
    """红线:main() 首个**可执行语句**必须是 _apply_resource_limits()(§3.5 C2/R3 修复)。

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


def test_main_runner_mode_stub_returns_2(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps({"strategyId": 1}))
    assert worker_server.main(["--mode", "runner"]) == 2


def test_run_backtest_stdout_prints_section8(monkeypatch, capsys):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)

    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "period": {"start": "", "end": ""}}

    from kwikquant_worker import event_loop as el

    monkeypatch.setattr(el.BacktestEventLoop, "run", lambda self, s, k, c: section8)

    # data_loader 里返回空
    from kwikquant_worker import worker_server as ws
    monkeypatch.setattr(
        "kwikquant_worker.data_loader.load_klines",
        lambda *a, **kw: [],
    )

    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbol": "BTC/USDT", "exchange": "BINANCE", "intervalValue": "1h",
        "startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-02T00:00:00Z",
        "parameters": "{}",
    }
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://kw")

    rc = ws.main(["--mode", "backtest"])
    assert rc == 0
    out = capsys.readouterr().out.strip()
    assert json.loads(out) == section8


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


def test_run_backtest_7303_exits_0(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda: None)
    from kwikquant.errors import KqBacktestTaskNotRunning
    from kwikquant_worker import event_loop as el

    def _raise(*a, **kw):
        raise KqBacktestTaskNotRunning(409, 7303, "not running")

    monkeypatch.setattr(el.BacktestEventLoop, "run", _raise)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", lambda *a, **kw: [])
    cfg = {"taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
           "symbol": "X", "exchange": "Y", "intervalValue": "1h",
           "startTime": "s", "endTime": "e", "parameters": "{}"}
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
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", lambda *a, **kw: [])
    cfg = {"taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
           "symbol": "X", "exchange": "Y", "intervalValue": "1h",
           "startTime": "s", "endTime": "e", "parameters": "{}"}
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


def test_instantiate_strategy_no_source_returns_baseline():
    from kwikquant_worker.strategy import Strategy

    ctx = MagicMock()
    s = worker_server._instantiate_strategy(None, ctx, {"k": "v"}, "BTC/USDT")
    assert isinstance(s, Strategy)
    assert s.parameters == {"k": "v"}
    assert s.default_symbol == "BTC/USDT"


def test_instantiate_strategy_source_finds_subclass():
    from kwikquant_worker.strategy import Strategy

    ctx = MagicMock()
    source = (
        "from kwikquant_worker.strategy import Strategy\n"
        "class MyStrat(Strategy):\n"
        "    def on_bar(self, bar):\n"
        "        pass\n"
    )
    s = worker_server._instantiate_strategy(source, ctx, {}, "BTC/USDT")
    assert isinstance(s, Strategy)
    assert type(s).__name__ == "MyStrat"


def test_instantiate_strategy_source_without_subclass_falls_back_to_baseline():
    from kwikquant_worker.strategy import Strategy

    ctx = MagicMock()
    s = worker_server._instantiate_strategy("x = 1\n", ctx, {}, "BTC/USDT")
    assert isinstance(s, Strategy) and type(s) is Strategy
