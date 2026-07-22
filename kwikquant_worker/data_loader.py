"""data_loader — 回测时 Worker 调 Java REST 拉 K 线(回测数据获取重构)。

废 Wave 8 §3.3 PG 直连(``WORKER_PG_READONLY_DSN`` + psycopg SELECT klines),改调
``GET /api/v1/backtests/{taskId}/klines``(X-Worker-Token 鉴权),Java 侧
``fetchKlineRangeApiFirst``(API-first + Caffeine,不查 klines 表)。空 list 表示区间无
历史数据(worker_server 据此 exit 2 → Java Runner 抛 BacktestNoMarketDataException →
markFailed 7304)。
"""

from __future__ import annotations


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
