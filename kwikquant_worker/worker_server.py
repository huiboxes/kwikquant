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
- runner:**拉取式 bootstrap**:env 仅留引导参数,配置经 GET
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
from types import MappingProxyType
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
    api_base = os.environ.get("KWIKQUANT_API_BASE", DEFAULT_API_BASE)

    if args.mode == "runner":
        if not service_token:
            print("[worker_server] WORKER_SERVICE_TOKEN missing", file=sys.stderr)
            return 1
        # runner 走拉取式 bootstrap:env 仅引导参数,配置经 GET /worker/bootstrap 拉取,
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
    service_token = service_token or cfg.pop("serviceToken", None)
    if not service_token:
        print("[worker_server] WORKER_SERVICE_TOKEN missing", file=sys.stderr)
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

    撮合本地化:event_loop 用 ``backtest/matching.py`` 本地撮合(配置经
    ``cfg["matchingConfig"]`` 下发),不再逐单 HTTP;HTTP 仅剩拉数据(/klines)与进度上报(/progress)。

    ``cfg["symbols"]`` 非空列表 → 组合(多标的)回测,派发 :func:`_run_portfolio_backtest`
    (策略契约 ``on_bars(ctx)``,共享现金池;仅 SPOT——PERP 组合在 Java 提交入口已拒,
    此处双保险,docs/perp-backtest-spec.md §1);否则单标的存量路径(契约 ``on_bar(bar, ctx)``)。

    ``cfg["marketType"] == "PERP"`` → 单标的 PERP 路径:额外拉已结算资金费序列
    (缺期 fail-closed,stderr ``FUNDING_DATA_MISSING:`` + exit 3 → Java markFailed 7308),
    连同 ``cfg["pairSpecs"]``(接受性快照)进引擎与 reproducibility。
    """
    symbols_raw = cfg.get("symbols")
    market_type = cfg.get("marketType") or "SPOT"
    if isinstance(symbols_raw, list) and symbols_raw:
        if market_type == "PERP":
            print(
                "PERP portfolio backtest not supported (仅单标的 PERP 回测,见 perp-backtest-spec §1)",
                file=sys.stderr,
            )
            return 1
        return _run_portfolio_backtest(cfg, service_token, api_base)

    from kwikquant.client import Auth, Client
    from kwikquant_worker.data_loader import FundingDataMissingError, load_funding_rates, load_klines
    from kwikquant_worker.event_loop import BacktestEventLoop
    from kwikquant_worker.strategy import BacktestContext

    task_id = int(cfg["taskId"])
    symbol = cfg["symbol"]
    exchange = cfg["exchange"]
    interval = cfg["intervalValue"]
    start = cfg["startTime"]
    end = cfg["endTime"]
    try:
        parameters = _parse_parameters(cfg.get("parameters"))
    except ValueError as e:
        print(f"[worker_server] {e}", file=sys.stderr)
        return 1
    initial_capital = _extract_initial_capital(parameters)
    strategy_source = cfg.get("strategySource") or parameters.get("__source__")

    client = Client(api_base, Auth.service_token(service_token))
    ctx = BacktestContext(
        client, task_id, exchange=exchange, market_type=market_type, symbol=symbol, params=parameters
    )

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

    # 时间轴正确性依赖"timestamp 严格升序"(NEXT_BAR 撮合与 PERP 资金费左开右闭归属都按序消费):
    # 乱序会让 bar 静默投递失败/期次错归属,零成交零告警——与组合路径同款防御性断言,fail-closed。
    ts_list = [str(k["timestamp"]) for k in klines]
    if any(a >= b for a, b in zip(ts_list, ts_list[1:])):
        print(f"[worker_server] klines timestamps not strictly ascending for {symbol}, aborting", file=sys.stderr)
        return 1

    # PERP:已结算资金费序列(缺期 fail-closed,绝不静默漏收,perp-backtest-spec §5.7)
    funding_periods = None
    if market_type == "PERP":
        try:
            funding_periods = load_funding_rates(
                client, task_id, exchange=exchange, symbol=symbol, start=start, end=end
            )
        except FundingDataMissingError as e:
            # exit 3 → Java BacktestResultParser 抛 BacktestFundingDataMissingException → markFailed 7308
            print(f"FUNDING_DATA_MISSING: {e}", file=sys.stderr)
            return 3
        except Exception as e:  # noqa: BLE001 — 端点/网络故障与缺期区分(通用失败 7300)
            print(f"[worker_server] load_funding_rates failed: {e!r}", file=sys.stderr)
            return 1

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
        # 撮合配置快照:Java Gateway 下发,event_loop 本地撮合引擎实际消费(起不再仅记录)
        "matching": cfg.get("matchingConfig") or {"status": "unavailable"},
        "execution": {
            # v5:事件时间轴(BAR/FUNDING 节点归并)+ 策略事件回调派发 + 资金费 mark 真值化
            # (期次行 mark_price 优先,fallback 归属 bar close;v4=PERP 账本/强平/资金费回放)
            "engineVersion": "backtest-event-loop-v5",
            "orderFillTiming": "NEXT_BAR",
        },
    }
    # 资金费序列与 pairSpecs 快照进 reproducibility(与 klines payload 同级,perp-backtest-spec §7);
    # 未下发时不写键(SPOT 存量输出形态不变)
    if funding_periods is not None:
        # mark_price 入 hash:v5 起期次行 mark_price 是结算输入(真值优先,spec §5.3)——
        # 不覆盖则同 fundingVersion 下数据订正会静默改变回测结果,可复现承诺失效
        # schema 入 hash 载荷:fundingVersion 单值即完整承诺"同数据+同行形态"——字段序/
        # 个数变更必然换 hash,跨 run 比对无需推断形态;fundingSchema 键外露行形态,
        # 消费方免解 hash 输入即可核对可比性(spec §7)
        funding_schema = ["funding_time", "settled_rate", "interval_seconds", "mark_price", "source"]
        funding_payload = json.dumps(
            {
                "schema": funding_schema,
                "rows": [
                    [
                        p.funding_time.isoformat(),
                        str(p.settled_rate),
                        p.interval_seconds,
                        None if p.mark_price is None else str(p.mark_price),
                        p.source,
                    ]
                    for p in funding_periods
                ],
            },
            separators=(",", ":"),
        )
        reproducibility["data"]["fundingVersion"] = (
            "sha256:" + hashlib.sha256(funding_payload.encode("utf-8")).hexdigest()
        )
        reproducibility["data"]["fundingSchema"] = funding_schema
        reproducibility["data"]["fundingPeriods"] = len(funding_periods)
    if cfg.get("pairSpecs"):
        reproducibility["pairSpecs"] = cfg["pairSpecs"]
    loop = BacktestEventLoop(
        initial_capital=initial_capital,
        symbol=symbol,
        timeframe=interval,
        params=parameters,
        reproducibility=reproducibility,
        matching_config=cfg.get("matchingConfig"),
        market_type=market_type,
        pair_specs=cfg.get("pairSpecs"),
        funding_periods=funding_periods,
        task_end=str(end),
    )

    try:
        module = _load_strategy_module(strategy_source, params=parameters)
        on_bar = module.__dict__["on_bar"]
        # 可选事件回调(on_fill/on_funding/on_liquidation,docs/strategy-api.md §8):
        # 未定义不派发;PERP 资金费/强平事件与 SPOT/PERP 成交事件按引擎时间轴派发
        cbs = _optional_callbacks(module)
        if market_type != "PERP":
            # SPOT 时间轴无 FUNDING 节点/强平段:定义了也永不派发——出声提示防作者
            # 误判"没有资金费事件发生"是数据问题(与组合路径同款纪律)
            for unused in ("on_funding", "on_liquidation"):
                if unused in cbs:
                    print(
                        f"[worker_server] SPOT backtest has no funding/liquidation events: "
                        f"{unused} defined but never dispatched",
                        file=sys.stderr,
                    )
        section8 = loop.run(on_bar, ctx, klines, **cbs)
    except Exception as e:  # noqa: BLE001
        print(f"[worker_server] event loop failed: {e!r}", file=sys.stderr)
        return 1
    finally:
        client.close()

    # stdout 回测结果 JSON(non-str Decimal 已在 event_loop 序列化为 str)
    print(json.dumps(section8, ensure_ascii=False))
    return 0


def _run_portfolio_backtest(cfg: dict, service_token: str, api_base: str) -> int:
    """组合(多标的)回测子进程:逐标的 load klines → PortfolioEventLoop(共享现金池 + 公共
    时间轴对齐,撮合语义与单标的完全一致)→ stdout 组合结果 JSON → exit 0。

    与 :func:`_run_backtest` 的差异仅在:多标的按 ``cfg["symbols"]`` 逐个拉数据、策略契约为
    ``on_bars(ctx)``、结果 JSON 带分标的成交/终仓。任一标的区间无数据 → exit 2(指明标的);
    数据获取复用现有单标的端点(逐标的调,Java 侧按任务快照标的集合守卫)。
    """
    from kwikquant.client import Auth, Client
    from kwikquant_worker.data_loader import load_klines
    from kwikquant_worker.portfolio import PortfolioContext, PortfolioEventLoop

    task_id = int(cfg["taskId"])
    symbols = [str(s) for s in cfg["symbols"]]
    exchange = cfg["exchange"]
    market_type = cfg.get("marketType") or "SPOT"
    interval = cfg["intervalValue"]
    start = cfg["startTime"]
    end = cfg["endTime"]
    try:
        parameters = _parse_parameters(cfg.get("parameters"))
    except ValueError as e:
        print(f"[worker_server] {e}", file=sys.stderr)
        return 1
    initial_capital = _extract_initial_capital(parameters)
    strategy_source = cfg.get("strategySource") or parameters.get("__source__")

    client = Client(api_base, Auth.service_token(service_token))
    ctx = PortfolioContext(
        client, task_id, exchange=exchange, market_type=market_type, symbols=symbols, params=parameters
    )

    series: dict[str, list[dict]] = {}
    try:
        for sym in symbols:
            klines = load_klines(
                client,
                task_id,
                exchange=exchange,
                market_type=market_type,
                symbol=sym,
                interval=interval,
                start=start,
                end=end,
            )
            if not klines:
                print(
                    f"NO_MARKET_DATA: {exchange} {market_type} {sym} {interval} {start}~{end} 无历史数据",
                    file=sys.stderr,
                )
                return 2
            # 时间轴正确性依赖"逐标的 timestamp 严格升序":乱序会让 bar 静默投递失败(零成交零告警),
            # 故在此 fail-closed。Java 两条取数路径都保证升序,这里是防御性断言。
            ts_list = [str(k["timestamp"]) for k in klines]
            if any(a >= b for a, b in zip(ts_list, ts_list[1:])):
                print(f"[worker_server] klines timestamps not strictly ascending for {sym}, aborting", file=sys.stderr)
                return 1
            series[sym] = klines
    except Exception as e:  # noqa: BLE001 — 明确 stderr + exit 1
        print(f"[worker_server] load_klines failed: {e!r}", file=sys.stderr)
        return 1

    strategy_hash = hashlib.sha256((strategy_source or "").encode("utf-8")).hexdigest()
    data_payload = json.dumps(series, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    data_hash = hashlib.sha256(data_payload.encode("utf-8")).hexdigest()
    reproducibility = {
        "schemaVersion": 1,
        "strategyCodeHash": f"sha256:{strategy_hash}",
        "data": {
            "requestedStart": str(start),
            "requestedEnd": str(end),
            "symbols": {
                sym: {
                    "actualStart": str(ks[0]["timestamp"]),
                    "actualEnd": str(ks[-1]["timestamp"]),
                    "bars": len(ks),
                }
                for sym, ks in series.items()
            },
            "version": f"sha256:{data_hash}",
        },
        "matching": cfg.get("matchingConfig") or {"status": "unavailable"},
        "execution": {
            # v2:on_fill 事件回调派发(带回调的策略成交/权益输出可变;v1=公共时间轴+共享现金池)
            "engineVersion": "portfolio-event-loop-v2",
            "orderFillTiming": "NEXT_BAR",
        },
    }
    loop = PortfolioEventLoop(
        initial_capital=initial_capital,
        symbols=symbols,
        timeframe=interval,
        params=parameters,
        reproducibility=reproducibility,
        matching_config=cfg.get("matchingConfig"),
    )

    try:
        module = _load_strategy_module(strategy_source, entrypoint="on_bars", params=parameters)
        on_bars = module.__dict__["on_bars"]
        cbs = _optional_callbacks(module)
        # 组合仅 SPOT:只有 FILL 事件(能力矩阵 docs/strategy-api.md §6)。定义了
        # on_funding/on_liquidation 不派发——出声提示防作者误以为组合有 PERP 事件
        for unused in ("on_funding", "on_liquidation"):
            if unused in cbs:
                print(
                    f"[worker_server] portfolio backtest is SPOT-only: {unused} defined but never dispatched",
                    file=sys.stderr,
                )
        section8 = loop.run(on_bars, ctx, series, on_fill=cbs.get("on_fill"))
    except Exception as e:  # noqa: BLE001
        print(f"[worker_server] event loop failed: {e!r}", file=sys.stderr)
        return 1
    finally:
        client.close()

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
    # recent API 返回 DESC；先转 ASC，再丢弃最新的可能未关闭 bar。
    ordered = sorted(raw, key=lambda k: k["openTime"])
    ctx.prefill_bars([_bar_from_kline(k) for k in ordered[:-1]])


def _run_runner(cfg: dict, service_token: str, api_base: str) -> int:
    """模拟/实盘 Runner:长驻,WS 订阅 /topic/kline → bar 关闭检测 → on_bar → trade.submit。

    流程:启 /health(供 WOS healthCheck)→ 实例化 on_bar → RunnerContext → StreamClient →
    RunnerEventLoop.run 长驻(asyncio.run StreamClient)。WS SUBSCRIBE /topic/kline → 后端
    StompSubscriptionInterceptor.onWsSubscribe 起 kline worker(computeIfAbsent);进程退出 / SIGKILL →
    WS session 断 → 后端 SessionDisconnectEvent → onWsSessionDisconnect 退 worker(无泄漏,去 persistent hack)。
    cfg 是 WorkerConfig JSON(strategyId/symbol/exchange/marketType/intervalValue/sourceCode/
    parameters/leverage/marginMode)。parameters fail-closed 解析后注入模块级 ``PARAMS`` 与
    ctx.params(与回测同源贯通);leverage/marginMode 是 V44 策略级绑定,作为 runner 下单
    未显式传参时的缺省值(不再把杠杆烘焙进源码)。
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
    try:
        parameters = _parse_parameters(cfg.get("parameters"))
    except ValueError as e:
        print(f"[worker_server] {e}", file=sys.stderr)
        return 1

    signals = HealthSignals(strategy_id, os.environ.get("WORKER_INCARNATION") or None)
    health = HealthServer(status_provider=signals.snapshot)
    health.start()

    # ws_url 从 api_base 推导(http→ws / https→wss,+/ws);WebSocketConfig endpoint /ws
    ws_url = api_base.replace("http://", "ws://").replace("https://", "wss://").rstrip("/") + "/ws"

    client = Client(api_base, Auth.service_token(service_token))
    try:
        module = _load_strategy_module(strategy_source, params=parameters)
        on_bar = module.__dict__["on_bar"]
        ctx = RunnerContext(
            client,
            strategy_id,
            exchange=exchange,
            market_type=market_type,
            symbol=symbol,
            health_signals=signals,
            params=parameters,
            # V44 策略级绑定(bootstrap 下发):PERP 订单未显式传参时的缺省 leverage/margin_mode
            leverage=cfg.get("leverage"),
            margin_mode=cfg.get("marginMode"),
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
        # 策略声明 WARMUP_BARS 时启动回填历史 K 线(慢速策略不等 N 天空转)
        _warmup_runner_history(
            ctx, client, module, exchange=exchange, market_type=market_type, symbol=symbol, interval=interval
        )
        stream = StreamClient(ws_url, Auth.service_token(service_token))
        # WS 驱动:StreamClient.run 内 WS SUBSCRIBE /topic/kline → 后端 onWsSubscribe 起 kline worker
        # (computeIfAbsent)。不再 REST POST /subscribe/kline(原 persistent hack,worker SIGKILL 后残留);
        # 进程退出 → WS 断 → 后端 onWsSessionDisconnect 自动退(无泄漏)。
        loop = RunnerEventLoop(health_signals=signals)
        # 事件回调(docs/strategy-api.md §8):userId/accountId 经 bootstrap 下发(WorkerBootstrapView),
        # 订阅 /topic/fills|liquidations|funding/{userId} 按需派发(未定义的回调不订阅);
        # topic 是 user 级(覆盖该用户全部账户),派发前按绑定 accountId+marketType+symbol 过滤
        cbs = _optional_callbacks(module)
        raw_user_id = cfg.get("userId")
        raw_account_id = cfg.get("accountId")
        loop.run(
            on_bar,
            ctx,
            stream,
            exchange=exchange,
            market_type=market_type,
            symbol=symbol,
            interval=interval,
            user_id=None if raw_user_id is None else int(raw_user_id),
            account_id=None if raw_account_id is None else int(raw_account_id),
            on_fill=cbs.get("on_fill"),
            on_funding=cbs.get("on_funding"),
            on_liquidation=cbs.get("on_liquidation"),
        )
        return 0
    except KeyboardInterrupt:
        return 0
    finally:
        # WS 驱动:不主动 REST unsubscribe;进程退出 → WS session 断 → 后端 onWsSessionDisconnect 退 worker
        health.stop()
        client.close()


def _parse_parameters(raw: Any) -> dict:
    """任务/策略 parameters 解析。**fail-closed**:非法 JSON 或非对象抛 ValueError
    (对齐"源码为空"纪律,caller 转 exit 1)——参数错传不再静默降级 {},防用户拿到
    一份"看似正常"实则参数全失效的报告。缺失(None)合法,返 {}。"""
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return raw
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as e:
        raise ValueError(f"parameters 非法 JSON,fail-closed 拒绝执行: {e}") from e
    if not isinstance(parsed, dict):
        raise ValueError(f"parameters 必须是 JSON 对象(键值对),实际是 {type(parsed).__name__}")
    return parsed


def _extract_initial_capital(parameters: dict) -> Decimal:
    v = parameters.get("initial_capital")
    if v is None:
        return Decimal("100000")
    try:
        return Decimal(str(v))
    except Exception:  # noqa: BLE001
        return Decimal("100000")


def _load_strategy_module(source: str | None, entrypoint: str = "on_bar", params: dict | None = None):
    """exec source_code 成 module,校验顶层入口函数存在后返回 module。

    ``entrypoint`` 单标的为 ``on_bar``(签名 ``on_bar(bar, ctx)``),组合(多标的)为
    ``on_bars``(签名 ``on_bars(ctx)``)。无 source / 无入口函数 → 抛(不静默 fallback
    baseline 空函数导致"0 信号"误导,让 worker exit 1 + stderr 明确报错)。返 module
    (而非只返函数)是为了让 runner 能读模块级常量(``WARMUP_BARS`` 启动回填根数)。

    ``params`` 在 **exec 前**注入模块级 ``PARAMS``(浅冻结 MappingProxyType,策略顶层
    ``FAST = int(PARAMS.get("fast", 5))`` 模式取参,docs/strategy-api.md);与 ctx.params
    同源。parameters 断链修复:此前 DB/REST/MCP 三层暴露的"策略参数"到 worker 即止,
    策略只能把参数烘焙进源码。
    """
    if not source:
        raise ValueError(
            f"策略源码为空,无法实例化 {entrypoint}(检查 strategy_codes.source_code 是否传到 worker)"
        )
    module_spec = importlib_util.spec_from_loader("__kq_user_strategy__", loader=None)
    module = importlib_util.module_from_spec(module_spec)  # type: ignore[arg-type]
    module.__dict__["PARAMS"] = MappingProxyType(dict(params or {}))
    exec(compile(source, "<user_strategy>", "exec"), module.__dict__)  # noqa: S102 — 受控子进程内
    if not callable(module.__dict__.get(entrypoint)):
        signature = "bar, ctx" if entrypoint == "on_bar" else "ctx"
        raise ValueError(f"策略源码未定义顶层 def {entrypoint}({signature}): 函数")
    return module


def _optional_callbacks(module) -> dict:
    """收集策略模块的可选事件回调(``on_fill``/``on_funding``/``on_liquidation``,
    docs/strategy-api.md §8)。

    未定义 = 引擎不派发(存量策略零影响);定义了但非 callable → ValueError fail-closed
    (与顶层入口缺失同纪律——回调名写错/赋值成非函数不得静默退化为"永不派发")。"""
    out: dict = {}
    for name in ("on_fill", "on_funding", "on_liquidation"):
        cb = module.__dict__.get(name)
        if cb is None:
            continue
        if not callable(cb):
            raise ValueError(f"策略源码顶层 {name} 必须是函数(定义了但不可调用)")
        out[name] = cb
    return out


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
    - 与 prefill 合并:按 openTime 去重后**整体排序替换**(prefill_bars),不是逐根 append——
      WARMUP_BARS > 预填根数时增量是更老的 bar,append 会排在最新 bar 之后,history() 时序损坏
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
    # 与 prefill(默认 200 根)按 openTime 去重合并:两通道拉的是同一"最近"区间
    merged = {str(getattr(b, "timestamp", "")): b for b in getattr(ctx, "_bars", [])}
    bars = sorted(raws, key=lambda k: str(k.get("openTime", "")))
    filled = 0
    for k in bars[:-1][-n:]:
        ts = str(k.get("openTime", ""))
        if ts in merged:
            continue
        merged[ts] = Bar(
            timestamp=ts,
            open=float(str(k.get("open", 0))),
            high=float(str(k.get("high", 0))),
            low=float(str(k.get("low", 0))),
            close=float(str(k.get("close", 0))),
            volume=float(str(k.get("volume", 0))),
        )
        filled += 1
    if filled:
        # 整体按 openTime 升序替换(不 append):老 bar 必须排在前面,history() 才是时序序列
        ctx.prefill_bars(sorted(merged.values(), key=lambda b: str(b.timestamp)))
    print(f"[runner] warmup filled {filled} closed bars (WARMUP_BARS={n})", file=sys.stderr)
    return filled


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
