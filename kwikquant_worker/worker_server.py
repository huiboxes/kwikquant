"""worker_server.py — Worker 进程入口。

**红线**:``main()`` **首行**调 ``resource.setrlimit``(防用户策略跑飞);
CLI ``--mode=backtest|runner`` 派发。回测跑完 stdout 输出回测结果 JSON;runner 长驻。

env:
- ``WORKER_SERVICE_TOKEN``:必需,Java WorkerTokenService 颁发。
- ``TASK_CONFIG_JSON``:必需,序列化 BacktestRunRequest(回测)或 WorkerConfig(runner)。
- ``KWIKQUANT_API_BASE``:Java REST 根 URL,默认 http://kwikquant-app:8080。
- ``KWIKQUANT_RLIMIT_CPU_SEC``/``KWIKQUANT_RLIMIT_AS_BYTES``:可选,默认 3600s / 2GB。
"""

from __future__ import annotations

import argparse
import hashlib
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
    """进程启动首要动作,设 CPU + 内存 rlimit,防用户策略跑飞。

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
    # 用户源码与 worker 同进程执行；协议 secret 读取完成后不再通过 os.environ 暴露。
    os.environ.pop("WORKER_SERVICE_TOKEN", None)
    os.environ.pop("TASK_CONFIG_JSON", None)
    os.environ.pop("WORKER_PG_READONLY_DSN", None)

    if args.mode == "backtest":
        return _run_backtest(cfg, service_token, api_base)
    return _run_runner(cfg, service_token, api_base)


def _run_backtest(cfg: dict, service_token: str, api_base: str) -> int:
    """回测子进程:load klines → BacktestEventLoop → stdout 回测结果 JSON → exit 0。"""
    from kwikquant.client import Auth, Client
    from kwikquant.errors import KqAuthError, KqBacktestTaskNotRunning
    from kwikquant_worker.data_loader import load_klines
    from kwikquant_worker.event_loop import BacktestEventLoop
    from kwikquant_worker.strategy import BacktestContext

    task_id = int(cfg["taskId"])
    symbol = cfg["symbol"]
    exchange = cfg["exchange"]
    market_type = cfg.get("marketType") or "SPOT"
    interval = cfg["intervalValue"]
    start = cfg["startTime"]
    end = cfg["endTime"]
    parameters = _parse_parameters(cfg.get("parameters"))
    initial_capital = _extract_initial_capital(parameters)
    strategy_source = cfg.get("strategySource") or parameters.get("__source__")

    client = Client(api_base, Auth.service_token(service_token))
    ctx = BacktestContext(client, task_id, exchange=exchange, market_type=market_type, symbol=symbol)

    try:
        klines = load_klines(
            client,
            task_id,
            exchange=exchange,
            market_type=market_type,
            symbol=symbol,
            interval=interval,
            start=start,
            end=end,
        )
    except Exception as e:  # noqa: BLE001 — 明确 stderr + exit 1
        print(f"[worker_server] load_klines failed: {e!r}", file=sys.stderr)
        return 1
    if not klines:
        # 区间无历史数据 → exit 2,Java Runner 抛 BacktestNoMarketDataException → markFailed 7304
        print(
            f"NO_MARKET_DATA: {exchange} {market_type} {symbol} {interval} {start}~{end} 无历史数据",
            file=sys.stderr,
        )
        return 2

    strategy_hash = hashlib.sha256((strategy_source or "").encode("utf-8")).hexdigest()
    data_payload = json.dumps(klines, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    data_hash = hashlib.sha256(data_payload.encode("utf-8")).hexdigest()
    reproducibility = {
        "schemaVersion": 1,
        "strategyCodeHash": f"sha256:{strategy_hash}",
        "data": {
            "requestedStart": str(start),
            "requestedEnd": str(end),
            "actualStart": str(klines[0]["timestamp"]),
            "actualEnd": str(klines[-1]["timestamp"]),
            "bars": len(klines),
            "version": f"sha256:{data_hash}",
        },
        "matching": cfg.get("matchingConfig") or {"status": "unavailable"},
        "execution": {
            "engineVersion": "backtest-event-loop-v2",
            "orderFillTiming": "NEXT_BAR",
        },
    }
    loop = BacktestEventLoop(
        initial_capital=initial_capital,
        symbol=symbol,
        timeframe=interval,
        params=parameters,
        reproducibility=reproducibility,
    )

    try:
        on_bar = _instantiate_strategy(strategy_source)
        section8 = loop.run(on_bar, ctx, klines)
    except KqBacktestTaskNotRunning:
        # exit 0(task 已结束,Java 检测 exit 0 查状态防重复 ReportService 调用)
        print("[worker_server] task not running (7303), exiting 0", file=sys.stderr)
        return 0
    except KqAuthError as e:
        # exit 1 让 Java markFailed
        print(f"[worker_server] token invalid (7301): {e.message}", file=sys.stderr)
        return 1
    except Exception as e:  # noqa: BLE001
        print(f"[worker_server] event loop failed: {e!r}", file=sys.stderr)
        return 1
    finally:
        client.close()

    # stdout 回测结果 JSON(non-str Decimal 已在 event_loop 序列化为 str)
    print(json.dumps(section8, ensure_ascii=False))
    return 0


def _run_runner(cfg: dict, service_token: str, api_base: str) -> int:
    """模拟/实盘 Runner:长驻,WS 订阅 /topic/kline → bar 关闭检测 → on_bar → trade.submit。

    流程:启 /health(供 WOS healthCheck)→ 实例化 on_bar → RunnerContext → StreamClient →
    RunnerEventLoop.run 长驻(asyncio.run StreamClient)。WS SUBSCRIBE /topic/kline → 后端
    StompSubscriptionInterceptor.onWsSubscribe 起 kline worker(computeIfAbsent);进程退出 / SIGKILL →
    WS session 断 → 后端 SessionDisconnectEvent → onWsSessionDisconnect 退 worker(无泄漏,去 persistent hack)。
    cfg 是 WorkerConfig JSON(strategyId/symbol/exchange/marketType/intervalValue/sourceCode/parameters)。
    """
    from kwikquant.client import Auth, Client
    from kwikquant.stream import StreamClient
    from kwikquant_worker.event_loop import RunnerEventLoop
    from kwikquant_worker.health_server import HealthServer
    from kwikquant_worker.runner_context import RunnerContext

    strategy_id = int(cfg.get("strategyId", 0))
    symbol = cfg.get("symbol", "")
    exchange = cfg.get("exchange", "")
    market_type = cfg.get("marketType", "SPOT")
    interval = cfg.get("intervalValue", "1h")
    strategy_source = cfg.get("sourceCode")

    health = HealthServer(status_provider=lambda: {"status": "ok", "strategyId": strategy_id})
    health.start()

    # ws_url 从 api_base 推导(http→ws / https→wss,+/ws);WebSocketConfig endpoint /ws
    ws_url = api_base.replace("http://", "ws://").replace("https://", "wss://").rstrip("/") + "/ws"

    client = Client(api_base, Auth.service_token(service_token))
    try:
        module = _load_strategy_module(strategy_source)
        on_bar = module.__dict__["on_bar"]
        ctx = RunnerContext(
            client, strategy_id, exchange=exchange, market_type=market_type, symbol=symbol
        )
        # 策略声明 WARMUP_BARS 时启动回填历史 K 线(慢速策略不等 N 天空转)
        _warmup_runner_history(
            ctx, client, module, exchange=exchange, market_type=market_type, symbol=symbol, interval=interval
        )
        stream = StreamClient(ws_url, Auth.service_token(service_token))
        # WS 驱动:StreamClient.run 内 WS SUBSCRIBE /topic/kline → 后端 onWsSubscribe 起 kline worker
        # (computeIfAbsent)。不再 REST POST /subscribe/kline(原 persistent hack,worker SIGKILL 后残留);
        # 进程退出 → WS 断 → 后端 onWsSessionDisconnect 自动退(无泄漏)。
        loop = RunnerEventLoop()
        loop.run(
            on_bar,
            ctx,
            stream,
            exchange=exchange,
            market_type=market_type,
            symbol=symbol,
            interval=interval,
        )
        return 0
    except KeyboardInterrupt:
        return 0
    finally:
        # WS 驱动:不主动 REST unsubscribe;进程退出 → WS session 断 → 后端 onWsSessionDisconnect 退 worker
        health.stop()
        client.close()


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


def _load_strategy_module(source: str | None):
    """exec source_code 成 module,校验顶层 ``on_bar(bar, ctx)`` 存在后返回 module。

    无 source / 无 on_bar → 抛(不静默 fallback baseline 空 on_bar 导致"0 信号"误导,
    让 worker exit 1 + stderr 明确报错)。返 module(而非只返函数)是为了让 runner 能读
    模块级常量(``WARMUP_BARS`` 启动回填根数)。
    """
    if not source:
        raise ValueError("策略源码为空,无法实例化 on_bar(检查 strategy_codes.source_code 是否传到 worker)")
    module_spec = importlib_util.spec_from_loader("__kq_user_strategy__", loader=None)
    module = importlib_util.module_from_spec(module_spec)  # type: ignore[arg-type]
    exec(compile(source, "<user_strategy>", "exec"), module.__dict__)  # noqa: S102 — 受控子进程内
    if not callable(module.__dict__.get("on_bar")):
        raise ValueError("策略源码未定义顶层 def on_bar(bar, ctx): 函数")
    return module


def _instantiate_strategy(source: str | None):
    """exec source_code,取顶层 ``on_bar(bar, ctx)`` 函数(回测用;runner 用 _load_strategy_module)。"""
    return _load_strategy_module(source).__dict__["on_bar"]


# Runner warmup 回填上限:REST /market/klines 单次 limit ≤1000,多拉的 1 根用于丢尾(活 bar)
_WARMUP_BARS_CAP = 999


def _extract_warmup_bars(module) -> int:
    """读策略模块级常量 ``WARMUP_BARS``(0/缺省=不回填),非法值按 0,封顶 999。"""
    try:
        n = int(module.__dict__.get("WARMUP_BARS", 0) or 0)
    except (TypeError, ValueError):
        return 0
    return max(0, min(n, _WARMUP_BARS_CAP))


def _warmup_runner_history(ctx, client, module, *, exchange, market_type, symbol, interval) -> int:
    """策略声明 WARMUP_BARS 时,启动经 REST 回填最近 N 根已关闭 K 线到 ctx(只灌历史不触发 on_bar)。

    - 排序:/market/klines 顺序不定(DB findRecent DESC / CCXT fallback ASC,消费方自排),按 openTime 升序
    - 丢尾根:最后一根可能是仍在进行中的活 bar,WS 订阅后会提供它(尾根替换→关闭推进),回填包含会重复
    - 失败容错:记 stderr 返 0 继续启动(策略自身 history 长度守卫兜底,不阻断 runner)
    """
    from kwikquant_worker.strategy import Bar

    n = _extract_warmup_bars(module)
    if n <= 0:
        return 0
    try:
        raws = client.data.klines_recent(exchange, market_type, symbol, interval, n + 1)
    except Exception as e:  # noqa: BLE001 — warmup 失败不阻断 runner 启动
        print(f"[runner] warmup fetch failed: {e!r}", file=sys.stderr)
        return 0
    bars = sorted(raws, key=lambda k: str(k.get("openTime", "")))
    filled = 0
    for k in bars[:-1][-n:]:
        ctx.set_bar(
            Bar(
                timestamp=str(k.get("openTime", "")),
                open=float(str(k.get("open", 0))),
                high=float(str(k.get("high", 0))),
                low=float(str(k.get("low", 0))),
                close=float(str(k.get("close", 0))),
                volume=float(str(k.get("volume", 0))),
            )
        )
        filled += 1
    print(f"[runner] warmup filled {filled} closed bars (WARMUP_BARS={n})", file=sys.stderr)
    return filled


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
