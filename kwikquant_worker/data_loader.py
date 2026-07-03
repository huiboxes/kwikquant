"""data_loader — 回测时 Worker 直连 Postgres 只读 klines(Wave 8 §3.3)。

生产 DSN 从 env ``WORKER_PG_READONLY_DSN`` 读取,独立只读 role(GRANT SELECT ON klines ONLY,
§3.3 数据格式消歧 R2 修复)。测试通过 ``connector`` 参数注入 fake 连接。
"""

from __future__ import annotations

import os
from typing import Callable

# psycopg 未安装时不阻塞导入(Docker 镜像预装,单元测试用 fake connector)
try:  # pragma: no cover
    import psycopg  # type: ignore
except ImportError:  # pragma: no cover
    psycopg = None


ConnectorFn = Callable[[str], "object"]  # 返回 DBAPI2-compatible connection


def load_klines(
    exchange: str,
    symbol: str,
    interval: str,
    start: str,
    end: str,
    *,
    dsn: str | None = None,
    connector: ConnectorFn | None = None,
) -> list[dict]:
    """SELECT klines WHERE (exchange,symbol,interval) BETWEEN [start,end]。

    Args:
        exchange/symbol/interval/start/end: 过滤条件;时间戳 ISO-8601 字符串。
        dsn: 覆盖默认 env ``WORKER_PG_READONLY_DSN``。
        connector: 测试注入 fake connection factory,签名 ``(dsn) -> connection``。

    Returns:
        list of ``{timestamp, open, high, low, close, volume}`` dict。
    """
    effective_dsn = dsn or os.environ.get("WORKER_PG_READONLY_DSN")
    if not effective_dsn:
        raise RuntimeError(
            "load_klines requires WORKER_PG_READONLY_DSN env or dsn kwarg (R2 修复,只读 role)"
        )
    if connector is None:
        if psycopg is None:
            raise RuntimeError("psycopg not installed; pass connector= for tests")
        connector = psycopg.connect  # type: ignore[assignment]

    sql = (
        "SELECT open_time, open, high, low, close, volume "
        "FROM klines "
        "WHERE exchange = %s AND symbol = %s AND interval = %s "
        "AND open_time >= %s AND open_time < %s "
        "ORDER BY open_time ASC"
    )
    conn = connector(effective_dsn)
    try:
        cur = conn.cursor()
        try:
            cur.execute(sql, (exchange, symbol, interval, start, end))
            rows = cur.fetchall()
        finally:
            cur.close()
    finally:
        conn.close()

    return [
        {
            "timestamp": _to_iso(row[0]),
            "open": row[1],
            "high": row[2],
            "low": row[3],
            "close": row[4],
            "volume": row[5],
        }
        for row in rows
    ]


def _to_iso(v) -> str:
    """timestamp 转 ISO-8601。datetime 走 isoformat,已是 str 直接返回。"""
    if hasattr(v, "isoformat"):
        return v.isoformat()
    return str(v)
