"""worker_server.py — Worker 进程入口(Wave 8 §3.3 + §3.5)。

**红线**:``main()`` **首行**调 ``resource.setrlimit``(C2/R3 修复,§3.5 实现锚点);
CLI ``--mode=backtest|runner`` 派发。回测跑完 stdout 输出 §8 JSON;runner 长驻。

env:
- ``WORKER_SERVICE_TOKEN``:必需,Java WorkerTokenService 颁发。
- ``TASK_CONFIG_JSON``:必需,序列化 BacktestRunRequest(§1.5)或 Runner strategy 配置。
- ``KWIKQUANT_API_BASE``:Java REST 根 URL,默认 http://kwikquant-app:8080。
- ``WORKER_PG_READONLY_DSN``:回测直连 Postgres 只读 DSN(§3.3 R2)。
- ``KWIKQUANT_RLIMIT_CPU_SEC``/``KWIKQUANT_RLIMIT_AS_BYTES``:可选,默认 3600s / 2GB。
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import resource
import sys
from decimal import Decimal
from importlib import util as importlib_util
from typing import Any

# 注意:模块级 import 保持极简,重逻辑放 main() 内;首行 rlimit 需在任何 import 之后立即触发


DEFAULT_CPU_SEC = 3600  # 1 hour
DEFAULT_AS_BYTES = 2 * 1024 * 1024 * 1024  # 2 GB
DEFAULT_API_BASE = "http://kwikquant-app:8080"


def _apply_resource_limits() -> None:
    """§3.5 R3/C2 修复:进程启动首要动作,设 CPU + 内存 rlimit,防用户策略跑飞。

    出现问题会抛 ValueError/OSError,让 caller 立即失败(非零 exit),不吞异常。
    """
    cpu = int(os.environ.get("KWIKQUANT_RLIMIT_CPU_SEC", DEFAULT_CPU_SEC))
    mem = int(os.environ.get("KWIKQUANT_RLIMIT_AS_BYTES", DEFAULT_AS_BYTES))
    resource.setrlimit(resource.RLIMIT_CPU, (cpu, cpu))
    # RLIMIT_AS 在 macOS 可能被限制;若不支持,记 stderr 但不阻塞(Docker Linux 生产环境总支持)
    try:
        resource.setrlimit(resource.RLIMIT_AS, (mem, mem))
    except (ValueError, OSError) as e:  # pragma: no cover — 平台特定
        print(f"[worker_server] RLIMIT_AS not applied ({e}); continuing", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    """入口。**首行必须调 :func:`_apply_resource_limits`**。返回 exit code。"""
    _apply_resource_limits()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
        stream=sys.stderr,
    )
    parser = argparse.ArgumentParser(prog="worker_server", description="KwikQuant Worker")
    parser.add_argument("--mode", choices=("backtest", "runner"), required=True)
    args = parser.parse_args(argv)

    service_token = os.environ.get("WORKER_SERVICE_TOKEN")
    if not service_token:
        print("[worker_server] WORKER_SERVICE_TOKEN missing", file=sys.stderr)
        return 1
    task_config = os.environ.get("TASK_CONFIG_JSON")
    if not task_config:
        print("[worker_server] TASK_CONFIG_JSON missing", file=sys.stderr)
        return 1
    try:
        cfg = json.loads(task_config)
    except json.JSONDecodeError as e:
        print(f"[worker_server] TASK_CONFIG_JSON malformed: {e}", file=sys.stderr)
        return 1

    api_base = os.environ.get("KWIKQUANT_API_BASE", DEFAULT_API_BASE)

    if args.mode == "backtest":
        return _run_backtest(cfg, service_token, api_base)
    return _run_runner(cfg, service_token, api_base)


def _run_backtest(cfg: dict, service_token: str, api_base: str) -> int:
    """回测子进程:load klines → BacktestEventLoop → stdout §8 JSON → exit 0。"""
    from kwikquant.client import Auth, Client
    from kwikquant.errors import KqAuthError, KqBacktestTaskNotRunning
    from kwikquant_worker.data_loader import load_klines
    from kwikquant_worker.event_loop import BacktestEventLoop
    from kwikquant_worker.strategy import BacktestContext, Strategy

    task_id = int(cfg["taskId"])
    symbol = cfg["symbol"]
    exchange = cfg["exchange"]
    interval = cfg["intervalValue"]
    start = cfg["startTime"]
    end = cfg["endTime"]
    parameters = _parse_parameters(cfg.get("parameters"))
    initial_capital = _extract_initial_capital(parameters)
    strategy_source = cfg.get("strategySource") or parameters.get("__source__")

    client = Client(api_base, Auth.service_token(service_token))
    ctx = BacktestContext(client, task_id)
    strategy: Strategy = _instantiate_strategy(strategy_source, ctx, parameters, symbol)

    loop = BacktestEventLoop(initial_capital=initial_capital, symbol=symbol, timeframe=interval)

    try:
        klines = load_klines(exchange, symbol, interval, start, end)
    except Exception as e:  # noqa: BLE001 — 明确 stderr + exit 1(§3.3 异常表)
        print(f"[worker_server] load_klines failed: {e!r}", file=sys.stderr)
        return 1

    try:
        section8 = loop.run(strategy, klines, client)
    except KqBacktestTaskNotRunning:
        # §3.3 exit 0(task 已结束,Java 检测 exit 0 查状态防重复 ReportService 调用)
        print("[worker_server] task not running (7303), exiting 0", file=sys.stderr)
        return 0
    except KqAuthError as e:
        # §3.3 exit 1 让 Java markFailed
        print(f"[worker_server] token invalid (7301): {e.message}", file=sys.stderr)
        return 1
    except Exception as e:  # noqa: BLE001
        print(f"[worker_server] event loop failed: {e!r}", file=sys.stderr)
        return 1
    finally:
        client.close()

    # stdout §8 JSON(non-str Decimal 已在 event_loop 序列化为 str)
    print(json.dumps(section8, ensure_ascii=False))
    return 0


def _run_runner(cfg: dict, service_token: str, api_base: str) -> int:
    """模拟/实盘 Runner:长驻,订阅 WS,调 strategy。§3.7 骨架:启 /health server + WS wiring 待补。

    /health :8081 供 Java WorkerOrchestratorService.healthCheckAll 探活(§3.7 healthCheck HTTP)。
    """
    from kwikquant_worker.health_server import HealthServer

    strategy_id = int(cfg.get("strategyId", 0))
    health = HealthServer(status_provider=lambda: {"status": "ok", "strategyId": strategy_id})
    health.start()
    try:
        # TODO §3.7 完整:StreamClient 订阅 /topic/ticks + on_tick → strategy.on_tick → trade.submit(/api/v1/orders)
        # 目前保留 /health 常驻,Runner 长循环骨架待 §3.7 后续 wiring。
        print("[worker_server] runner mode /health up; event loop wiring pending (§3.7)", file=sys.stderr)
        return 3  # 3 = runner startup ok but WS wiring pending;Java 侧不作 markFailed(见 markRunnerPending)
    finally:
        health.stop()


def _parse_parameters(raw: Any) -> dict:
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return raw
    try:
        return json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return {}


def _extract_initial_capital(parameters: dict) -> Decimal:
    v = parameters.get("initial_capital")
    if v is None:
        return Decimal("100000")
    try:
        return Decimal(str(v))
    except Exception:  # noqa: BLE001
        return Decimal("100000")


def _instantiate_strategy(
    source: str | None, ctx, parameters: dict, symbol: str
):
    """从 source_code 动态实例化 Strategy 子类;source=None 时返回 no-op Strategy(默认 baseline)。"""
    from kwikquant_worker.strategy import Strategy

    if not source:
        s = Strategy(ctx=ctx, default_symbol=symbol)
        s.parameters = parameters
        return s
    module_spec = importlib_util.spec_from_loader("__kq_user_strategy__", loader=None)
    module = importlib_util.module_from_spec(module_spec)  # type: ignore[arg-type]
    exec(compile(source, "<user_strategy>", "exec"), module.__dict__)  # noqa: S102 — 受控子进程内
    for _name, obj in module.__dict__.items():
        if isinstance(obj, type) and issubclass(obj, Strategy) and obj is not Strategy:
            inst = obj(ctx=ctx, default_symbol=symbol)
            inst.parameters = parameters
            return inst
    s = Strategy(ctx=ctx, default_symbol=symbol)
    s.parameters = parameters
    return s


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
