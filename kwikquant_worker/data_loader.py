"""data_loader — 回测时 Worker 调 Java REST 拉数据(K 线 + PERP 资金费序列)。

原方案是 PG 直连(``WORKER_PG_READONLY_DSN`` + psycopg SELECT klines),已废弃,改调
``GET /api/v1/backtests/{taskId}/klines``(X-Worker-Token 鉴权),Java 侧
``fetchKlineRangeDbFirst``(DB-first + API 补漏;拉过的区间落 klines 表,数据快照真复现,
交易所 API 抖动不再经缓存路径连带 markFailed)。空 list 表示区间无历史数据
(worker_server 据此 exit 2 → Java Runner 抛 BacktestNoMarketDataException → markFailed 7304)。

PERP 资金费序列:``GET /api/v1/backtests/{taskId}/funding-rates``(DB 直读已结算行,
覆盖完整性由 Java 提交/执行预检保证);worker 侧运行期缺期复检 fail-closed
(:class:`FundingDataMissingError` → worker_server exit 3 → markFailed 7308,绝不静默漏收,
docs/perp-backtest-spec.md §5.7)。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

from kwikquant_worker.backtest.perp_ledger import FundingPeriod, parse_instant


def load_klines(
    client,
    task_id: int,
    *,
    exchange: str,
    market_type: str,
    symbol: str,
    interval: str,
    start: str,
    end: str,
) -> list[dict]:
    """通过 Java REST 拉历史 K 线区间。

    Args:
        client: kwikquant ``Client``(X-Worker-Token 已由 Auth.service_token 注入)。
        task_id: 回测任务 ID(endpoint 路径参数,WorkerTokenFilter 校验 taskType=BACKTEST)。
        exchange/market_type/symbol/interval/start/end: 查询参数(ISO-8601 时间串)。

    Returns:
        ``list[dict]``,每项 ``{timestamp, open, high, low, close, volume}``
        (Java Kline openTime 已映射成 timestamp,供 event_loop 消费);空 list 表示区间无数据。
    """
    return client.trade.get_klines(
        task_id,
        exchange=exchange,
        market_type=market_type,
        symbol=symbol,
        interval=interval,
        start=start,
        end=end,
    )


class FundingDataMissingError(RuntimeError):
    """PERP 资金费序列运行期缺失(空序列/头缺/相邻缺期/尾缺)。

    worker_server 捕获后 stderr ``FUNDING_DATA_MISSING:`` 前缀 + exit 3 →
    Java BacktestResultParser 抛 BacktestFundingDataMissingException → markFailed(7308)。
    """


def load_funding_rates(
    client,
    task_id: int,
    *,
    exchange: str,
    symbol: str,
    start: str,
    end: str,
    query_end: str | None = None,
) -> list[FundingPeriod]:
    """拉已结算资金费序列并做运行期缺期复检(fail-closed)。

    Args:
        start/end: 任务快照区间(缺期检测基准)。
        query_end: 查询终点(默认 end + 24h 前瞻缓冲——末根 bar 的期次可落在任务 end
            之后,左开右闭归属;与 Java 端点守卫的缓冲一致)。

    Returns:
        ASC ``list[FundingPeriod]``(settled_rate 恒非空)。

    Raises:
        FundingDataMissingError: 序列为空或存在缺期(预检双卡点后的运行期异常态,
            通常是数据被删/采集停摆;绝不静默漏收)。
    """
    q_end = query_end or (
        (parse_instant(end) + timedelta(hours=24)).isoformat().replace("+00:00", "Z")
    )
    rows = client.trade.get_funding_rates(
        task_id, exchange=exchange, symbol=symbol, start=start, end=q_end
    )
    periods = [
        FundingPeriod(
            funding_time=parse_instant(r["funding_time"]),
            settled_rate=Decimal(str(r["settled_rate"])),
            interval_seconds=None if r.get("interval_seconds") is None else int(r["interval_seconds"]),
            mark_price=None if r.get("mark_price") is None else Decimal(str(r["mark_price"])),
            source=str(r.get("source") or "EXCHANGE"),
        )
        for r in rows
        if r.get("funding_time") is not None and r.get("settled_rate") is not None
    ]
    # 缺期检测只看任务区间 [start, end] 内的行:(end, q_end] 前瞻缓冲行供末根 bar 归属消费,
    # 缓冲区自身的洞不影响任务区间完整性,参与检测会假阳性 exit 3(且"缩短区间"出路对其无效)
    end_dt = parse_instant(end)
    in_range = [p for p in periods if p.funding_time <= end_dt]
    gaps = find_funding_gaps(in_range, start=start, end=end)
    if gaps:
        raise FundingDataMissingError(
            f"{exchange} {symbol} [{start}, {end}) 资金费序列缺期: " + "; ".join(gaps)
        )
    return periods


def find_funding_gaps(
    periods: list[FundingPeriod],
    *,
    start: str,
    end: str,
    now: datetime | None = None,
) -> list[str]:
    """运行期缺期检测:空序列 / 头缺 / 相邻间隔超 1.5×interval / 尾缺。

    容差取相邻两行 interval 较大者的 1.5 倍——交易所期次周期存在历史切换(如 4h→8h),
    切换点间隔合法翻倍,不误报;真缺期(整期丢失)间隔 ≥ 2×interval 必命中。
    interval 未声明的行跳过其参与的间隔检测(Java 预检已 fail-closed 拒无 interval 序列,
    此处仅防御)。尾缺基准 = min(end, now − interval):最新期次可能尚未到结算时刻
    (与 Java 预检的近端宽限同口径,防误报)。
    """
    if not periods:
        return ["序列为空(区间内无任何已结算期次)"]
    gaps: list[str] = []
    start_dt, end_dt = parse_instant(start), parse_instant(end)
    now_dt = now or datetime.now(timezone.utc)

    first_interval = periods[0].interval_seconds
    if first_interval:
        head_slack = timedelta(seconds=first_interval * 1.5)
        if periods[0].funding_time - start_dt > head_slack:
            gaps.append(f"头缺: 首期 {periods[0].funding_time.isoformat()} 距区间起点超 {head_slack}")

    for a, b in zip(periods, periods[1:]):
        if not a.interval_seconds or not b.interval_seconds:
            continue
        limit = timedelta(seconds=max(a.interval_seconds, b.interval_seconds) * 1.5)
        if b.funding_time - a.funding_time > limit:
            gaps.append(f"缺期: ({a.funding_time.isoformat()}, {b.funding_time.isoformat()}) 间隔超 {limit}")

    last_interval = periods[-1].interval_seconds
    if last_interval:
        grace = now_dt - timedelta(seconds=last_interval)
        tail_base = min(end_dt, grace)
        if periods[-1].funding_time + timedelta(seconds=last_interval * 1.5) < tail_base:
            gaps.append(f"尾缺: 末期 {periods[-1].funding_time.isoformat()} 未覆盖至 {tail_base.isoformat()}")
    return gaps
