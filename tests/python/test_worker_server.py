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


def test_main_backtest_reads_service_token_from_stdin_config(monkeypatch):
    """Docker 回测不把 token 放进 env/docker inspect；stdin 配置中的 token 必须可用。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.delenv("WORKER_SERVICE_TOKEN", raising=False)
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    import io

    monkeypatch.setattr(
        "sys.stdin", io.StringIO(json.dumps({"taskId": 1, "serviceToken": "stdin-token"}))
    )
    observed = {}

    def fake_run(cfg, service_token, api_base):
        observed["service_token"] = service_token
        return 0

    monkeypatch.setattr(worker_server, "_run_backtest", fake_run)

    assert worker_server.main(["--mode", "backtest"]) == 0
    assert observed["service_token"] == "stdin-token"


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


def test_run_runner_passes_params_and_leverage_binding(monkeypatch):
    """parameters 贯通 runner:bootstrap 的 parameters 注入模块 PARAMS(on_bar.__globals__ 可见)
    + 进 RunnerContext.params;V44 策略级 leverage/marginMode 绑定进 ctx(PERP 订单缺省值)。"""
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

    import kwikquant_worker.event_loop as el_mod

    run_calls = {}

    class FakeLoop:
        def __init__(self, *a, **kw):
            pass

        def run(self, on_bar, ctx, stream, **kw):
            run_calls["on_bar"] = on_bar
            run_calls["ctx"] = ctx

    monkeypatch.setattr(el_mod, "RunnerEventLoop", FakeLoop)
    monkeypatch.setattr(worker_server, "_prefill_history", lambda *a, **kw: None)

    # 捕获 RunnerContext 构造 kwargs(params/leverage/margin_mode 透传断言)
    import kwikquant_worker.runner_context as rc_mod

    ctx_kwargs = {}
    real_ctx = rc_mod.RunnerContext

    def spy_ctx(client, strategy_id, **kw):
        ctx_kwargs.update(kw)
        return real_ctx(client, strategy_id, **kw)

    monkeypatch.setattr(rc_mod, "RunnerContext", spy_ctx)

    bootstrap_cfg = {
        "strategyId": 5,
        "strategyName": "perp-strat",
        "sourceCode": "FAST = int(PARAMS.get('fast', 5))\ndef on_bar(bar, ctx):\n    pass",
        "symbol": "BTC/USDT:USDT",
        "exchange": "OKX",
        "marketType": "PERP",
        "intervalValue": "1h",
        "parameters": '{"fast": 7}',
        "leverage": 10,
        "marginMode": "ISOLATED",
        "apiBaseUrl": "http://localhost:9999",
    }
    monkeypatch.setattr(worker_server, "_fetch_bootstrap", lambda token, base: bootstrap_cfg)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://localhost:9999")

    assert worker_server.main(["--mode", "runner"]) == 0
    # 模块级 PARAMS 注入(exec 前):策略顶层已取到 fast=7
    assert dict(run_calls["on_bar"].__globals__["PARAMS"]) == {"fast": 7}
    # ctx 透传:params + V44 绑定
    assert dict(ctx_kwargs["params"]) == {"fast": 7}
    assert ctx_kwargs["leverage"] == 10
    assert ctx_kwargs["margin_mode"] == "ISOLATED"
    assert run_calls["ctx"].params["fast"] == 7


def test_run_runner_invalid_parameters_exits_1(monkeypatch, capsys):
    """runner bootstrap parameters 非法 JSON → exit 1 + stderr(fail-closed,与回测同纪律)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    bootstrap_cfg = {
        "strategyId": 5,
        "sourceCode": "def on_bar(bar, ctx):\n    pass",
        "symbol": "BTC/USDT",
        "exchange": "OKX",
        "marketType": "SPOT",
        "intervalValue": "1h",
        "parameters": "{bogus",
        "apiBaseUrl": "http://localhost:9999",
    }
    monkeypatch.setattr(worker_server, "_fetch_bootstrap", lambda token, base: bootstrap_cfg)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)

    assert worker_server.main(["--mode", "runner"]) == 1
    assert "parameters 非法 JSON" in capsys.readouterr().err


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


def test_prefill_history_orders_desc_response_and_drops_newest_bar(monkeypatch):
    """recent API 返回 DESC；预填必须转 ASC，并丢弃最新的可能未关闭 bar。"""
    monkeypatch.delenv("KWIKQUANT_RUNNER_PREFILL_BARS", raising=False)
    from kwikquant_worker.runner_context import RunnerContext

    client = MagicMock()
    client.data.ohlcv.return_value = [_prefill_kline("T3", 30), _prefill_kline("T2", 20), _prefill_kline("T1", 10)]
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")

    worker_server._prefill_history(
        client, ctx, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="1h"
    )

    assert ctx.history("close", 99) == [10.0, 20.0]
    assert ctx.history("close", 1) == [20.0]
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
        observed["match_config"] = self.match_config
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
    # v5:事件时间轴(BAR/FUNDING 节点归并)+ 事件回调派发 + 资金费 mark 真值化
    assert snapshot["execution"]["engineVersion"] == "backtest-event-loop-v5"
    assert observed["params"] == {}
    # matchingConfig 下发 → 本地撮合引擎实际消费
    assert observed["match_config"].market_slippage_bps == Decimal("5")
    assert observed["match_config"].taker_fee_rate == Decimal("0.002")
    assert observed["match_config"].maker_fee_rate == Decimal("0.001")  # 缺省键回落默认


def test_run_backtest_spot_with_perp_callbacks_warns_never_dispatched(monkeypatch, capsys):
    """SPOT 回测定义 on_funding/on_liquidation:照常跑(rc=0)但出声提示永不派发
    (SPOT 时间轴无 FUNDING 节点/强平段;与组合路径同款纪律,防作者误判数据问题)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "period": {"start": "", "end": ""}}

    from kwikquant_worker import event_loop as el
    from kwikquant_worker import worker_server as ws

    captured_cbs = {}

    def fake_run(self, on_bar, ctx, klines, **cbs):
        captured_cbs.update(cbs)
        return section8

    monkeypatch.setattr(el.BacktestEventLoop, "run", fake_run)
    monkeypatch.setattr(
        "kwikquant_worker.data_loader.load_klines",
        lambda *a, **kw: [{"timestamp": "t", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"}],
    )
    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbol": "BTC/USDT", "exchange": "BINANCE", "intervalValue": "1h",
        "startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-02T00:00:00Z",
        "parameters": "{}",
        "strategySource": (
            "def on_bar(bar, ctx):\n    pass\n"
            "def on_fill(ev, ctx):\n    pass\n"
            "def on_funding(ev, ctx):\n    pass\n"
            "def on_liquidation(ev, ctx):\n    pass\n"
        ),
    }
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://kw")

    rc = ws.main(["--mode", "backtest"])
    assert rc == 0
    captured = capsys.readouterr()
    assert "on_funding defined but never dispatched" in captured.err
    assert "on_liquidation defined but never dispatched" in captured.err
    assert json.loads(captured.out.strip()) == section8
    # 接线锁:三回调全部透传 loop.run(**cbs)——丢键/错键时引擎静默不派发且全部测试仍绿,
    # 此断言是 worker_server 装配层唯一的判别点(引擎层语义另有 test_event_timeline 锁)
    assert set(captured_cbs) == {"on_fill", "on_funding", "on_liquidation"}
    assert all(callable(v) for v in captured_cbs.values())


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


def test_run_backtest_event_loop_failure_exits_1(monkeypatch):
    """event loop 抛异常(含 token 失效等)→ exit 1 让 Java markFailed。
    撮合本地化后无 7303 特殊路径(原 exit 0 分支随 HTTP 撮合删除)。"""
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


def test_parse_parameters_fail_closed_on_invalid():
    """非法 JSON / 非对象 → ValueError(caller 转 exit 1)——不再静默降级 {} 出"参数全失效却看似正常"的报告。"""
    with pytest.raises(ValueError, match="非法 JSON"):
        worker_server._parse_parameters("bogus")
    with pytest.raises(ValueError, match="JSON 对象"):
        worker_server._parse_parameters("[1,2]")


def test_run_backtest_invalid_parameters_exits_1(monkeypatch, capsys):
    """parameters 非法 JSON → exit 1 + stderr 明确(fail-closed,对齐"源码为空"纪律)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbol": "BTC/USDT", "exchange": "BINANCE", "intervalValue": "1h",
        "startTime": "s", "endTime": "e", "parameters": "{bogus",
        "strategySource": "def on_bar(bar, ctx):\n    pass",
    }
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    assert worker_server.main(["--mode", "backtest"]) == 1
    assert "parameters 非法 JSON" in capsys.readouterr().err


def test_load_strategy_module_injects_params_before_exec():
    """PARAMS 在 exec 前注入模块命名空间(策略顶层常量可取参),浅冻结只读。"""
    module = worker_server._load_strategy_module(
        "FAST = int(PARAMS.get('fast', 5))\ndef on_bar(bar, ctx):\n    return\n",
        params={"fast": 12},
    )
    assert module.__dict__["FAST"] == 12
    assert module.__dict__["PARAMS"]["fast"] == 12
    with pytest.raises(TypeError):
        module.__dict__["PARAMS"]["fast"] = 1  # MappingProxyType 只读


def test_load_strategy_module_params_default_empty():
    module = worker_server._load_strategy_module("def on_bar(bar, ctx):\n    return\n")
    assert dict(module.__dict__["PARAMS"]) == {}


def test_load_strategy_module_no_source_raises():
    with pytest.raises(ValueError, match="策略源码为空"):
        worker_server._load_strategy_module(None)


def test_load_strategy_module_source_with_on_bar_returns_module():
    source = "def on_bar(bar, ctx):\n    pass\n"
    module = worker_server._load_strategy_module(source)
    assert callable(module.__dict__["on_bar"])


def test_load_strategy_module_source_without_on_bar_raises():
    with pytest.raises(ValueError, match="未定义顶层 def on_bar"):
        worker_server._load_strategy_module("x = 1\n")


def test_optional_callbacks_collects_defined_and_rejects_non_callable():
    """事件回调收集(docs/strategy-api.md §8):未定义不收集;非 callable fail-closed。"""
    module = worker_server._load_strategy_module(
        "def on_bar(bar, ctx):\n    pass\n"
        "def on_fill(fill, ctx):\n    pass\n"
        "def on_liquidation(ev, ctx):\n    pass\n"
    )
    cbs = worker_server._optional_callbacks(module)
    assert set(cbs) == {"on_fill", "on_liquidation"}
    assert callable(cbs["on_fill"])
    assert "on_funding" not in cbs  # 未定义 = 不派发(存量策略零影响)
    bad = worker_server._load_strategy_module("def on_bar(bar, ctx):\n    pass\non_fill = 3\n")
    with pytest.raises(ValueError, match="on_fill"):
        worker_server._optional_callbacks(bad)


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


def test_warmup_runner_history_merges_older_bars_before_newer():
    """WARMUP_BARS > 预填根数:增量是**更老**的 bar,必须合并在时序前端(整体排序替换),
    逐根 append 会排在最新 bar 之后 → history() 时序损坏、指标静默失真(R2 P1)。"""
    from kwikquant_worker.runner_context import RunnerContext
    from kwikquant_worker.event_loop import _bar_from_kline

    client = MagicMock()
    # warmup 拉 4+1 根:00/01/02/03 + 04(活 bar 丢尾);prefill 已灌 03/04?? —— 模拟 prefill 只灌了最新 2 根
    client.data.klines_recent.return_value = [
        _kline("2026-08-16T04:00:00Z", "5"),  # 活 bar,丢尾
        _kline("2026-08-16T00:00:00Z", "1"),
        _kline("2026-08-16T03:00:00Z", "4"),
        _kline("2026-08-16T01:00:00Z", "2"),
        _kline("2026-08-16T02:00:00Z", "3"),
    ]
    module = worker_server._load_strategy_module("WARMUP_BARS = 4\ndef on_bar(bar, ctx):\n    return\n")
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    # prefill 灌了最新 1 根已收盘(03:00)
    ctx.prefill_bars([_bar_from_kline(_kline("2026-08-16T03:00:00Z", "4"))])

    filled = worker_server._warmup_runner_history(
        ctx, client, module, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="1h"
    )

    assert filled == 3  # 00/01/02 新增,03 去重,04 丢尾
    # 时序完整升序:老 bar 在前,新 bar 在后(不是 append 到 03 之后)
    assert ctx.history("close", 10) == [1.0, 2.0, 3.0, 4.0]


def test_warmup_runner_history_dedupes_against_prefill():
    """prefill(默认 200 根)×WARMUP_BARS 双通道拉同一"最近"区间:set_bar 是 append,
    不按 openTime 去重会把同一段 bar 灌两遍 → history 尾段重复,指标静默失真(F15 存量 bug)。"""
    from kwikquant_worker.runner_context import RunnerContext
    from kwikquant_worker.event_loop import _bar_from_kline

    client = MagicMock()
    client.data.klines_recent.return_value = [
        _kline("2026-08-16T02:00:00Z", "3"),
        _kline("2026-08-16T00:00:00Z", "1"),
        _kline("2026-08-16T01:00:00Z", "2"),
    ]
    module = worker_server._load_strategy_module("WARMUP_BARS = 2\ndef on_bar(bar, ctx):\n    return\n")
    ctx = RunnerContext(client, 1, exchange="OKX", market_type="SPOT", symbol="BTC/USDT")
    # 模拟 prefill 已灌入同一区间(00:00/01:00 已收盘;02:00 是活 bar 被 prefill 丢尾)
    ctx.prefill_bars([_bar_from_kline(_kline("2026-08-16T00:00:00Z", "1")),
                      _bar_from_kline(_kline("2026-08-16T01:00:00Z", "2"))])

    filled = worker_server._warmup_runner_history(
        ctx, client, module, exchange="OKX", market_type="SPOT", symbol="BTC/USDT", interval="15m"
    )

    assert filled == 0  # 全部与 prefill 重复,零追加
    assert ctx.history("close", 10) == [1.0, 2.0]  # 无重复尾段


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


# ---------------------------------------------------------------- 组合(多标的)回测派发


def _portfolio_cfg(**overrides):
    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbols": ["AAA/USDT", "BBB/USDT"],
        "exchange": "BINANCE", "marketType": "SPOT", "intervalValue": "1h",
        "startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-02T00:00:00Z",
        "parameters": "{}",
        "strategySource": "def on_bars(ctx):\n    pass",
        "matchingConfig": {"marketSlippageBps": "5", "takerFeeRate": "0.002"},
    }
    cfg.update(overrides)
    return cfg


def _portfolio_klines_loader(missing: str | None = None):
    """按 symbol kwarg 返回各标的数据;指定标的返空(模拟区间无数据)。"""

    def loader(client, task_id, *, exchange, market_type, symbol, interval, start, end):
        if symbol == missing:
            return []
        return [
            {"timestamp": "2024-01-01T00:00:00Z", "open": "100", "high": "101", "low": "99", "close": "100", "volume": "10"},
            {"timestamp": "2024-01-01T01:00:00Z", "open": "100", "high": "105", "low": "100", "close": "104", "volume": "12"},
        ]

    return loader


def test_run_backtest_portfolio_dispatch_and_reproducibility(monkeypatch, capsys):
    """cfg 带 symbols → 组合派发:PortfolioEventLoop 消费,stdout 输出组合 section8,
    复现快照含 per-symbol data + portfolio 引擎版本。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)

    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "period": {"start": "", "end": ""}}
    from kwikquant_worker import portfolio as pf

    observed = {}

    def fake_run(self, on_bars, ctx, series, *, on_fill=None):
        observed["reproducibility"] = self.reproducibility
        observed["symbols"] = self.symbols
        observed["match_config"] = self.match_config
        observed["series_keys"] = sorted(series.keys())
        observed["on_fill"] = on_fill
        return section8

    monkeypatch.setattr(pf.PortfolioEventLoop, "run", fake_run)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _portfolio_klines_loader())

    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_portfolio_cfg()))
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://kw")

    rc = worker_server.main(["--mode", "backtest"])
    assert rc == 0
    assert json.loads(capsys.readouterr().out.strip()) == section8
    assert observed["symbols"] == ["AAA/USDT", "BBB/USDT"]
    assert observed["series_keys"] == ["AAA/USDT", "BBB/USDT"]
    assert observed["on_fill"] is None  # 策略未定义事件回调 → 不派发
    snap = observed["reproducibility"]
    # v2:on_fill 事件回调派发能力(带回调的策略输出可变,快照须与 v1 区分)
    assert snap["execution"]["engineVersion"] == "portfolio-event-loop-v2"
    assert snap["execution"]["orderFillTiming"] == "NEXT_BAR"
    assert snap["strategyCodeHash"].startswith("sha256:")
    assert snap["data"]["version"].startswith("sha256:")
    assert snap["data"]["symbols"]["AAA/USDT"]["bars"] == 2
    assert snap["data"]["symbols"]["BBB/USDT"]["actualStart"] == "2024-01-01T00:00:00Z"
    assert observed["match_config"].market_slippage_bps == Decimal("5")


def test_run_backtest_portfolio_passes_defined_on_fill(monkeypatch, capsys):
    """组合策略定义 on_fill → 装配透传 callable(接线锁:on_fill= 丢键/错键即红;
    未定义情形由上一测试锁 is None,引擎层派发语义另有 test_event_timeline 锁)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)

    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "period": {"start": "", "end": ""}}
    from kwikquant_worker import portfolio as pf

    observed = {}

    def fake_run(self, on_bars, ctx, series, *, on_fill=None):
        observed["on_fill"] = on_fill
        return section8

    monkeypatch.setattr(pf.PortfolioEventLoop, "run", fake_run)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _portfolio_klines_loader())
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv(
        "TASK_CONFIG_JSON",
        json.dumps(
            _portfolio_cfg(strategySource="def on_bars(ctx):\n    pass\ndef on_fill(ev, ctx):\n    pass\n")
        ),
    )
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://kw")

    assert worker_server.main(["--mode", "backtest"]) == 0
    capsys.readouterr()
    assert callable(observed["on_fill"])


def test_run_backtest_portfolio_end_to_end_real_engine(monkeypatch, capsys):
    """不起 stub 的真引擎端到端:组合策略逐标的市价买入,stdout JSON 含分标的成交与权益曲线。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _portfolio_klines_loader())
    monkeypatch.setattr(
        "kwikquant_worker.portfolio.PortfolioContext.report_progress", lambda self, processed, total: None
    )

    source = (
        "def on_bars(ctx):\n"
        "    for s in ctx.symbols():\n"
        "        if ctx.bar(s) is not None and ctx.position(symbol=s).qty == 0:\n"
        "            ctx.place_order(symbol=s, side='BUY', order_type='MARKET', amount='1')\n"
        "            return\n"
    )
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_portfolio_cfg(strategySource=source)))
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://kw")

    rc = worker_server.main(["--mode", "backtest"])
    assert rc == 0
    out = json.loads(capsys.readouterr().out.strip())
    assert out["symbols"] == ["AAA/USDT", "BBB/USDT"]
    # t0 下 AAA 单(NEXT_BAR t1 成交)→ t1 步已持仓,策略 return;仅 1 笔成交
    assert len(out["trades"]) == 1
    assert out["trades"][0]["symbol"] == "AAA/USDT"
    assert out["trades"][0]["time"] == "2024-01-01T01:00:00Z"
    assert len(out["equity_curve"]) == 2
    assert Decimal(out["positions"]["AAA/USDT"]["qty"]) == Decimal("1")


def test_run_backtest_portfolio_missing_on_bars_exits_1(monkeypatch, capsys):
    """组合任务策略未定义 on_bars → exit 1(stderr 明确),不静默跑空策略。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _portfolio_klines_loader())
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv(
        "TASK_CONFIG_JSON", json.dumps(_portfolio_cfg(strategySource="def on_bar(bar, ctx):\n    pass"))
    )
    assert worker_server.main(["--mode", "backtest"]) == 1
    assert "on_bars" in capsys.readouterr().err


def test_run_backtest_portfolio_symbol_without_data_exits_2(monkeypatch, capsys):
    """任一标的区间无数据 → exit 2 + NO_MARKET_DATA(指明标的),Java markFailed 7304。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _portfolio_klines_loader(missing="BBB/USDT"))
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_portfolio_cfg()))

    assert worker_server.main(["--mode", "backtest"]) == 2
    err = capsys.readouterr().err
    assert "NO_MARKET_DATA" in err and "BBB/USDT" in err


def test_run_backtest_portfolio_load_failure_exits_1(monkeypatch):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    monkeypatch.setattr(
        "kwikquant_worker.data_loader.load_klines",
        lambda *a, **kw: (_ for _ in ()).throw(RuntimeError("api down")),
    )
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_portfolio_cfg()))
    assert worker_server.main(["--mode", "backtest"]) == 1


def test_run_backtest_portfolio_non_ascending_klines_exits_1(monkeypatch, capsys):
    """防御性断言:某标的 K 线时间戳非严格升序 → fail-closed exit 1(不静默产出空回测)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)

    def loader(client, task_id, *, exchange, market_type, symbol, interval, start, end):
        if symbol == "AAA/USDT":
            # 乱序(先 01:00 后 00:00)
            return [
                {"timestamp": "2024-01-01T01:00:00Z", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"},
                {"timestamp": "2024-01-01T00:00:00Z", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"},
            ]
        return [
            {"timestamp": "2024-01-01T00:00:00Z", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"},
            {"timestamp": "2024-01-01T01:00:00Z", "open": "1", "high": "1", "low": "1", "close": "1", "volume": "1"},
        ]

    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", loader)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_portfolio_cfg()))

    assert worker_server.main(["--mode", "backtest"]) == 1
    assert "not strictly ascending" in capsys.readouterr().err


def test_run_backtest_empty_symbols_list_falls_back_single(monkeypatch, capsys):
    """symbols=[](空)不触发组合路径,仍走单标的(symbol 缺省由 Java 校验拦截,此处仅验派发分支)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "period": {"start": "", "end": ""}}
    from kwikquant_worker import event_loop as el

    monkeypatch.setattr(el.BacktestEventLoop, "run", lambda self, on_bar, ctx, klines: section8)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _portfolio_klines_loader())
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    cfg = _portfolio_cfg(symbols=[], symbol="AAA/USDT", strategySource="def on_bar(bar, ctx):\n    pass")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    assert worker_server.main(["--mode", "backtest"]) == 0
    assert json.loads(capsys.readouterr().out.strip()) == section8


def test_load_portfolio_module_requires_on_bars():
    with pytest.raises(ValueError, match="on_bars"):
        worker_server._load_strategy_module("def on_bar(bar, ctx):\n    pass\n", entrypoint="on_bars")
    with pytest.raises(ValueError, match="策略源码为空"):
        worker_server._load_strategy_module(None, entrypoint="on_bars")
    m = worker_server._load_strategy_module("def on_bars(ctx):\n    pass\n", entrypoint="on_bars")
    assert callable(m.__dict__["on_bars"])


# ---------------- PERP 回测装配(marketType=PERP 分支) ----------------


def _perp_cfg(**over) -> dict:
    cfg = {
        "taskId": 1, "strategyId": 1, "strategyCodeId": 1, "userId": 1,
        "symbol": "BTC/USDT", "exchange": "OKX", "marketType": "PERP", "intervalValue": "1h",
        "startTime": "2024-01-01T00:00:00Z", "endTime": "2024-01-02T00:00:00Z",
        "parameters": "{}",
        "strategySource": "def on_bar(bar, ctx):\n    pass",
        "pairSpecs": {"BTC/USDT": {"symbol": "BTC/USDT", "marketType": "PERP",
                                    "minQty": "0.001", "maxQty": None, "tickSize": "0.1",
                                    "stepSize": "0.001", "maxLeverage": 100, "contractSize": "0.01"}},
    }
    cfg.update(over)
    return cfg


def _stub_klines(monkeypatch):
    monkeypatch.setattr(
        "kwikquant_worker.data_loader.load_klines",
        lambda *a, **kw: [{"timestamp": "2024-01-01T00:00:00Z", "open": "42000",
                           "high": "42000", "low": "42000", "close": "42000", "volume": "1"}],
    )


def test_run_backtest_perp_assembles_funding_and_pair_specs(monkeypatch, capsys):
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    _stub_klines(monkeypatch)

    from kwikquant_worker.backtest.perp_ledger import FundingPeriod, parse_instant

    periods = [
        FundingPeriod(funding_time=parse_instant("2024-01-01T08:00:00Z"),
                      settled_rate=Decimal("0.0001"), interval_seconds=28800,
                      mark_price=None, source="PROXY_BINANCE"),
    ]
    observed = {}

    def fake_load_funding(*a, **kw):
        observed["funding_kwargs"] = kw
        return periods

    monkeypatch.setattr("kwikquant_worker.data_loader.load_funding_rates", fake_load_funding)

    from kwikquant_worker import event_loop as el

    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "warnings": []}

    def fake_run(self, on_bar, ctx, klines):
        observed["market_type"] = self.market_type
        observed["pair_spec"] = self._pair_spec
        observed["funding_periods"] = self._funding_periods
        observed["reproducibility"] = self.reproducibility
        return section8

    monkeypatch.setattr(el.BacktestEventLoop, "run", fake_run)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_perp_cfg()))

    assert worker_server.main(["--mode", "backtest"]) == 0
    assert capsys.readouterr().out.strip() == json.dumps(section8)

    # 引擎装配:PERP 模式 + pairSpec 快照 + funding 序列
    assert observed["market_type"] == "PERP"
    assert observed["pair_spec"] is not None
    assert observed["pair_spec"].min_qty == Decimal("0.001")
    assert observed["pair_spec"].max_leverage == 100
    assert observed["funding_periods"] == periods
    assert observed["funding_kwargs"]["exchange"] == "OKX"
    assert observed["funding_kwargs"]["symbol"] == "BTC/USDT"
    # reproducibility:funding 序列 hash + pairSpecs 快照(与 klines payload 同级)
    rep = observed["reproducibility"]
    assert rep["data"]["fundingVersion"].startswith("sha256:")
    assert rep["data"]["fundingPeriods"] == 1
    assert rep["pairSpecs"]["BTC/USDT"]["maxLeverage"] == 100


def test_run_backtest_portfolio_perp_callbacks_warn_never_dispatched(monkeypatch, capsys):
    """组合(仅 SPOT)定义 on_funding/on_liquidation:照常跑但出声提示永不派发
    (与单标的 SPOT 路径对称,防作者误以为组合有 PERP 事件)。"""
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    section8 = {"trades": [], "equity_curve": [], "metrics": {}, "period": {"start": "", "end": ""}}

    from kwikquant_worker import portfolio as pf

    monkeypatch.setattr(pf.PortfolioEventLoop, "run", lambda self, on_bars, ctx, series, **kw: section8)
    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _portfolio_klines_loader())

    cfg = _portfolio_cfg()
    cfg["strategySource"] = (
        "def on_bars(ctx):\n    pass\n"
        "def on_funding(ev, ctx):\n    pass\n"
        "def on_liquidation(ev, ctx):\n    pass\n"
    )
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(cfg))
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://kw")

    rc = worker_server.main(["--mode", "backtest"])
    assert rc == 0
    captured = capsys.readouterr()
    assert "on_funding defined but never dispatched" in captured.err
    assert "on_liquidation defined but never dispatched" in captured.err
    assert json.loads(captured.out.strip()) == section8


def test_run_backtest_perp_funding_hash_covers_mark_price(monkeypatch):
    """v5 起 mark_price 是资金费结算输入(真值化,spec §5.3)→ fundingVersion hash 必须覆盖:
    同期次同费率、仅 mark_price 不同的快照必须可区分——否则"同快照 ⇒ 同结果"承诺被
    数据订正/回填静默打破(reproducibility 审计口径失效)。"""
    from kwikquant_worker import event_loop as el
    from kwikquant_worker.backtest.perp_ledger import FundingPeriod, parse_instant

    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    _stub_klines(monkeypatch)

    hashes = []
    task_ends = []
    for mark in ("42000", "43000"):
        periods = [
            FundingPeriod(funding_time=parse_instant("2024-01-01T08:00:00Z"), settled_rate=Decimal("0.0001"),
                          interval_seconds=28800, mark_price=Decimal(mark)),
        ]
        monkeypatch.setattr("kwikquant_worker.data_loader.load_funding_rates", lambda *a, **kw: list(periods))
        captured = {}

        def fake_run(self, on_bar, ctx, klines, **cbs):
            captured["rep"] = self.reproducibility
            captured["task_end"] = self._task_end
            return {"trades": [], "equity_curve": [], "metrics": {}, "warnings": []}

        monkeypatch.setattr(el.BacktestEventLoop, "run", fake_run)
        monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
        monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_perp_cfg()))
        assert worker_server.main(["--mode", "backtest"]) == 0
        hashes.append(captured["rep"]["data"]["fundingVersion"])
        task_ends.append(captured["task_end"])

    assert hashes[0] != hashes[1]  # mark_price 差异必须进快照 hash
    # task_end 装配透传(尾部漏期警示的诊断锚点,spec §8)
    assert task_ends == [_perp_cfg()["endTime"]] * 2


def test_run_runner_passes_user_id_and_event_callbacks(monkeypatch):
    """runner 装配:bootstrap userId/accountId + 策略事件回调透传 RunnerEventLoop.run
    (user_id 是 user 级事件 topic 的 destination 段;account_id 是账户过滤键——user 级
    topic 覆盖该用户全部账户,防 PAPER/LIVE 跨账户泄漏)。"""
    import kwikquant_worker.event_loop as el_mod

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

    monkeypatch.setattr(el_mod, "RunnerEventLoop", FakeLoop)
    bootstrap_cfg = {
        "strategyId": 5,
        "userId": 42,
        "accountId": 7,
        "strategyName": "test-strat",
        "sourceCode": (
            "def on_bar(bar, ctx):\n    pass\n"
            "def on_fill(fill, ctx):\n    pass\n"
            "def on_liquidation(ev, ctx):\n    pass\n"
        ),
        "symbol": "BTC/USDT",
        "exchange": "OKX",
        "marketType": "PERP",
        "intervalValue": "1h",
        "parameters": "{}",
        "apiBaseUrl": "http://localhost:9999",
    }
    monkeypatch.setattr(worker_server, "_fetch_bootstrap", lambda token, base: bootstrap_cfg)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "t")
    monkeypatch.delenv("TASK_CONFIG_JSON", raising=False)
    monkeypatch.setenv("KWIKQUANT_API_BASE", "http://localhost:9999")

    assert worker_server.main(["--mode", "runner"]) == 0
    kw = run_calls["kwargs"]
    assert kw["user_id"] == 42
    assert kw["account_id"] == 7
    assert callable(kw["on_fill"]) and callable(kw["on_liquidation"])
    assert kw["on_funding"] is None  # 未定义 = None(不订阅不派发)


def test_run_backtest_perp_funding_missing_exits_3(monkeypatch, capsys):
    # 运行期缺期(预检后数据被删)→ stderr FUNDING_DATA_MISSING: + exit 3 → Java markFailed 7308
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    _stub_klines(monkeypatch)

    from kwikquant_worker.data_loader import FundingDataMissingError

    def fake_load_funding(*a, **kw):
        raise FundingDataMissingError("OKX BTC/USDT 缺期: 首缺 2024-01-01T08:00:00Z")

    monkeypatch.setattr("kwikquant_worker.data_loader.load_funding_rates", fake_load_funding)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_perp_cfg()))

    assert worker_server.main(["--mode", "backtest"]) == 3
    err = capsys.readouterr().err
    assert "FUNDING_DATA_MISSING:" in err and "缺期" in err


def test_run_backtest_perp_funding_endpoint_error_exits_1(monkeypatch, capsys):
    # 端点/网络故障与缺期区分:通用失败 exit 1(7300),不误报 7308
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)
    _stub_klines(monkeypatch)

    def fake_load_funding(*a, **kw):
        raise RuntimeError("connection refused")

    monkeypatch.setattr("kwikquant_worker.data_loader.load_funding_rates", fake_load_funding)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv("TASK_CONFIG_JSON", json.dumps(_perp_cfg()))

    assert worker_server.main(["--mode", "backtest"]) == 1
    assert "FUNDING_DATA_MISSING" not in capsys.readouterr().err


def test_run_backtest_perp_portfolio_rejected_before_any_fetch(monkeypatch, capsys):
    # PERP 组合双保险拒(Java 提交入口已拒;worker 装配层防御,不发任何数据请求)
    monkeypatch.setattr(worker_server, "_apply_resource_limits", lambda *_: None)

    def _boom(*a, **kw):
        raise AssertionError("PERP portfolio must be rejected before data fetch")

    monkeypatch.setattr("kwikquant_worker.data_loader.load_klines", _boom)
    monkeypatch.setenv("WORKER_SERVICE_TOKEN", "wt-1")
    monkeypatch.setenv(
        "TASK_CONFIG_JSON",
        json.dumps(_perp_cfg(symbols=["BTC/USDT", "ETH/USDT"], symbol=None)),
    )

    assert worker_server.main(["--mode", "backtest"]) == 1
    assert "PERP portfolio backtest not supported" in capsys.readouterr().err
