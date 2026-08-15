"""worker_server.py — Worker 进程入口。

**红线**:``main()`` **首行**调 ``resource.setrlimit``(防用户策略跑飞);
CLI ``--mode=backtest|runner`` 派发。回测跑完 stdout 输出回测结果 JSON;runner 长驻。

**rlimit 分 mode**:RLIMIT_CPU 是**累计 CPU 时间**,仅适用于有限的回测进程;
runner 是长驻进程,累计达限会被 SIGKILL(策略状态静默丢失)→ runner 模式**不设**
RLIMIT_CPU,内存约束交给 RLIMIT_AS / 容器 --memory。

配置下发(**按 mode 分通道**):
- backtest:``TASK_CONFIG_JSON`` env **或 stdin**(env 优先)。DockerBacktestRunner 走
  stdin(docker run -i):策略源码可达 1MB,超 Linux argv+env ~128KB 上限;且 env 经
  docker inspect 对宿主 docker 组可见,stdin 两者皆免。
- runner:**拉取式 bootstrap**(Wave 1.4 ③):env 仅留引导参数,配置经 GET
  /api/v1/worker/bootstrap 拉取(用 WORKER_SERVICE_TOKEN 鉴权)。sourceCode 不进 env,
  解 E2BIG + docker inspect 可窥。detached(docker run -d)stdin 不工作,故 runner 不能
  走 stdin,bootstrap 拉取是 detached 场景的配置下发方式。
- ``WORKER_SERVICE_TOKEN``:env 必需,Java WorkerTokenService 颁发(backtest 拉数据/进度上报 + runner bootstrap/下单共用)。
- ``KWIKQUANT_API_BASE``:Java REST 根 URL,默认 http://kwikquant-app:8080。
- ``KWIKQUANT_RLIMIT_CPU_SEC``/``KWIKQUANT_RLIMIT_AS_BYTES``:可选,默认 3600s(仅 backtest) / 2GB。
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
# runner 重启历史 bar 预填根数(覆盖 MA200 等常见窗口);env KWIKQUANT_RUNNER_PREFILL_BARS 可调,<=0 关闭
DEFAULT_PREFILL_BARS = 200


def _apply_resource_limits(argv: list[str] | None = None) -> None:
    """进程启动首要动作,设 rlimit 防用户策略跑飞。**按 mode 分限制**:

    - backtest(默认):RLIMIT_CPU(累计 CPU 时间,有限进程合理) + RLIMIT_AS。
    - runner:**不设 RLIMIT_CPU**——长驻进程累计达限会被 SIGKILL,策略状态静默丢失;
      内存仍受 RLIMIT_AS / 容器 --memory 约束。

    argv 预解析 ``--mode``(此时 argparse 尚未执行,保持 main 首行调用形态);
    argv=None 时嗅探 sys.argv。出现 setrlimit 问题抛 ValueError/OSError,caller 立即失败,不吞异常。
    """
    args = argv if argv is not None else sys.argv[1:]
    is_runner = any(a == "runner" or a == "--mode=runner" for a in args) or (
        "--mode" in args and len(args) > args.index("--mode") + 1 and args[args.index("--mode") + 1] == "runner"
    )
    mem = int(os.environ.get("KWIKQUANT_RLIMIT_AS_BYTES", DEFAULT_AS_BYTES))
    if not is_runner:
        cpu = int(os.environ.get("KWIKQUANT_RLIMIT_CPU_SEC", DEFAULT_CPU_SEC))
        resource.setrlimit(resource.RLIMIT_CPU, (cpu, cpu))
    # RLIMIT_AS 在 macOS 可能被限制;若不支持,记 stderr 但不阻塞(Docker Linux 生产环境总支持)
    try:
        resource.setrlimit(resource.RLIMIT_AS, (mem, mem))
    except (ValueError, OSError) as e:  # pragma: no cover — 平台特定
        print(f"[worker_server] RLIMIT_AS not applied ({e}); continuing", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    """入口。**首行必须调 :func:`_apply_resource_limits`**。返回 exit code。

    配置下发**按 mode 分通道**:runner 走拉取式 bootstrap(GET /worker/bootstrap),backtest 走
    env/stdin(stdin 一次性)。两者共用 env ``WORKER_SERVICE_TOKEN`` + ``KWIKQUANT_API_BASE``。
    """
    _apply_resource_limits(argv)

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
    api_base = os.environ.get("KWIKQUANT_API_BASE", DEFAULT_API_BASE)

    if args.mode == "runner":
        # runner 走拉取式 bootstrap(Wave 1.4 ③):env 仅引导参数,配置经 GET /worker/bootstrap 拉取,
        # sourceCode 不进 env(解 E2BIG + docker inspect 可窥)。detached(docker run -d)stdin 不工作,
        # 故 runner 不能走 stdin,bootstrap 拉取是 detached 场景的配置下发方式。pop secrets 在拉取前
        # (service_token 已存局部);bootstrap 失败(401/404/网络)→ exit 1 → docker --rm 清理。
        _clear_worker_secrets()
        try:
            cfg = _fetch_bootstrap(service_token, api_base)
        except Exception as e:  # noqa: BLE001 — bootstrap 失败 → exit 1
            print(f"[worker_server] bootstrap failed: {e!r}", file=sys.stderr)
            return 1
        return _run_runner(cfg, service_token, api_base)

    # backtest:env TASK_CONFIG_JSON 优先(dev/test 子进程路径);缺失时读 stdin
    # (DockerBacktestRunner docker run -i,避开 env 128KB 上限与 docker inspect 可窥)。
    # stdin 不可读(pytest capture 等场景)视为未提供配置 → 走下方 return 1 明确报错。
    task_config = os.environ.get("TASK_CONFIG_JSON")
    if not task_config:
        try:
            task_config = sys.stdin.read()
        except (OSError, ValueError):
            task_config = ""
    if not task_config:
        print("[worker_server] task config missing (env TASK_CONFIG_JSON 与 stdin 均为空)", file=sys.stderr)
        return 1
    try:
        cfg = json.loads(task_config)
    except json.JSONDecodeError as e:
        print(f"[worker_server] TASK_CONFIG_JSON malformed: {e}", file=sys.stderr)
        return 1
    # 用户源码与 worker 同进程执行；协议 secret 读取完成后不再通过 os.environ 暴露。
    _clear_worker_secrets()
    return _run_backtest(cfg, service_token, api_base)


def _clear_worker_secrets() -> None:
    """用户源码执行前清 env 秘密(防 /proc/<pid>/environ 窃取 + 策略 os.environ 读)。

    协议 secret(service_token/task_config/pg_dsn)读取完成后调:runner bootstrap 拉完配置后、
    backtest 解析完 task_config 后。pop 后局部变量仍持有(传 _run_*),env 不再暴露。
    """
    os.environ.pop("WORKER_SERVICE_TOKEN", None)
    os.environ.pop("TASK_CONFIG_JSON", None)
    os.environ.pop("WORKER_PG_READONLY_DSN", None)


def _fetch_bootstrap(service_token: str, api_base: str) -> dict:
    """runner 启动后 GET /api/v1/worker/bootstrap 拉取启动配置(含 sourceCode),替代 env TASK_CONFIG_JSON。

    用 RUNNER service token 鉴权(:class:`kwikquant.client.Auth`.service_token 发 ``X-Worker-Token`` header);
    后端 ``WorkerBootstrapController`` 据 token entry 的 strategyId 反查 configRegistry 返回
    ``WorkerBootstrapView``(camelCase 字段,与原 env TASK_CONFIG_JSON 同构,**不含 serviceToken**——
    worker 已有 env token)。Client._handle_response 已 unwrap envelope ``{code,data}`` → 返回 data dict。

    失败(401 token 坏/404 config registry 无/网络)抛 → main catch → exit 1 → docker --rm 清理。
    """
    from kwikquant.client import Auth, Client

    client = Client(api_base, Auth.service_token(service_token))
    try:
        cfg = client.get("/api/v1/worker/bootstrap")
    finally:
        client.close()
    if not isinstance(cfg, dict) or "strategyId" not in cfg:
        raise RuntimeError("bootstrap response missing strategyId")
    return cfg


def _run_backtest(cfg: dict, service_token: str, api_base: str) -> int:
    """回测子进程:load klines → BacktestEventLoop(本地撮合)→ stdout 回测结果 JSON → exit 0。

    撮合本地化(Wave 2.2):event_loop 用 ``backtest/matching.py`` 本地撮合(配置经
    ``cfg["matchingConfig"]`` 下发),不再逐单 HTTP;HTTP 仅剩拉数据(/klines)与进度上报(/progress)。
    """
    from kwikquant.client import Auth, Client
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
        # 撮合配置快照:Java Gateway 下发,event_loop 本地撮合引擎实际消费(Wave 2.2 起不再仅记录)
        "matching": cfg.get("matchingConfig") or {"status": "unavailable"},
        "execution": {
            "engineVersion": "backtest-event-loop-v3",
            "orderFillTiming": "NEXT_BAR",
        },
    }
    loop = BacktestEventLoop(
        initial_capital=initial_capital,
        symbol=symbol,
        timeframe=interval,
        params=parameters,
        reproducibility=reproducibility,
        matching_config=cfg.get("matchingConfig"),
    )

    try:
        on_bar = _instantiate_strategy(strategy_source)
        section8 = loop.run(on_bar, ctx, klines)
    except Exception as e:  # noqa: BLE001
        print(f"[worker_server] event loop failed: {e!r}", file=sys.stderr)
        return 1
    finally:
        client.close()

    # stdout 回测结果 JSON(non-str Decimal 已在 event_loop 序列化为 str)
    print(json.dumps(section8, ensure_ascii=False))
    return 0


def _prefill_history(client, ctx, *, exchange: str, market_type: str, symbol: str, interval: str) -> None:
    """WS 连接前拉最近 N+1 根历史 K 线,丢末根(可能未关闭),prefill 进 ``ctx._bars``。

    消除 runner 重启"失忆":重启后 ``_bars`` 空,``history()`` 返 [] 直到攒够 N 根(1h 策略 = N 小时
    静默);预填最近 N 根已关闭 bar → ``history()`` 立即可用。**丢末根**防与 WS 首根未关闭 bar 重复
    ``set_bar``(WS 首根仅缓存,关闭后 append;若该 openTime 已在预填 → 重复污染指标)。

    失败(网络/无数据/超时)不阻断 runner——仅丢 warmup,WS 路径照常(stderr 记录)。
    """
    from kwikquant_worker.event_loop import _bar_from_kline

    n = int(os.environ.get("KWIKQUANT_RUNNER_PREFILL_BARS", DEFAULT_PREFILL_BARS))
    if n <= 0:
        return
    try:
        raw = client.data.ohlcv(
            exchange=exchange, market_type=market_type, symbol=symbol, interval=interval, limit=n + 1
        )
    except Exception as e:  # noqa: BLE001 — 预填失败不阻断 runner
        print(f"[worker_server] prefill ohlcv failed: {e!r}", file=sys.stderr)
        return
    # 末根可能未关闭(WS 仍推同 openTime):丢弃。宁少一根(罕见无未关闭 bar 时丢一已关闭 bar)
    # 也不要重复(重复污染 history/指标)。raw[:-1] 对空 list 也安全(→ [] → _index=-1)。
    ctx.prefill_bars([_bar_from_kline(k) for k in raw[:-1]])


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
    from kwikquant_worker.health_signals import HealthSignals
    from kwikquant_worker.runner_context import RunnerContext

    strategy_id = int(cfg.get("strategyId", 0))
    symbol = cfg.get("symbol", "")
    exchange = cfg.get("exchange", "")
    market_type = cfg.get("marketType", "SPOT")
    interval = cfg.get("intervalValue", "1h")
    strategy_source = cfg.get("sourceCode")

    signals = HealthSignals(strategy_id)
    health = HealthServer(status_provider=signals.snapshot)
    health.start()

    # ws_url 从 api_base 推导(http→ws / https→wss,+/ws);WebSocketConfig endpoint /ws
    ws_url = api_base.replace("http://", "ws://").replace("https://", "wss://").rstrip("/") + "/ws"

    client = Client(api_base, Auth.service_token(service_token))
    try:
        on_bar = _instantiate_strategy(strategy_source)
        ctx = RunnerContext(
            client,
            strategy_id,
            exchange=exchange,
            market_type=market_type,
            symbol=symbol,
            health_signals=signals,
        )
        # WS 连接前预填历史 bar(消除重启失忆):拉最近 N 根已关闭 bar 填 ctx._bars,
        # 重启后 history() 立即可用(无需攒 N 根 warmup)。失败不阻断,WS 路径照常。
        _prefill_history(
            client,
            ctx,
            exchange=exchange,
            market_type=market_type,
            symbol=symbol,
            interval=interval,
        )
        stream = StreamClient(ws_url, Auth.service_token(service_token))
        # WS 驱动:StreamClient.run 内 WS SUBSCRIBE /topic/kline → 后端 onWsSubscribe 起 kline worker
        # (computeIfAbsent)。不再 REST POST /subscribe/kline(原 persistent hack,worker SIGKILL 后残留);
        # 进程退出 → WS 断 → 后端 onWsSessionDisconnect 自动退(无泄漏)。
        loop = RunnerEventLoop(health_signals=signals)
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


def _instantiate_strategy(source: str | None):
    """exec source_code,取顶层 ``on_bar(bar, ctx)`` 函数。

    无 source / 无 on_bar → 抛(不静默 fallback baseline 空 on_bar 导致"0 信号"误导,
    让 worker exit 1 + stderr 明确报错)。函数式:ctx 由 event_loop 调用时传入,on_bar 不持 ctx。
    """
    if not source:
        raise ValueError("策略源码为空,无法实例化 on_bar(检查 strategy_codes.source_code 是否传到 worker)")
    module_spec = importlib_util.spec_from_loader("__kq_user_strategy__", loader=None)
    module = importlib_util.module_from_spec(module_spec)  # type: ignore[arg-type]
    exec(compile(source, "<user_strategy>", "exec"), module.__dict__)  # noqa: S102 — 受控子进程内
    on_bar = module.__dict__.get("on_bar")
    if not callable(on_bar):
        raise ValueError("策略源码未定义顶层 def on_bar(bar, ctx): 函数")
    return on_bar


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
