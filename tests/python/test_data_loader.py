"""data_loader — Postgres 只读 klines(Wave 8 §3.3 R2)。"""

from __future__ import annotations

import datetime as dt
from unittest.mock import MagicMock

import pytest

from kwikquant_worker.data_loader import load_klines


def _fake_connector_returning(rows):
    conn = MagicMock()
    cursor = MagicMock()
    cursor.fetchall.return_value = rows
    conn.cursor.return_value = cursor
    return MagicMock(return_value=conn), conn, cursor


def test_load_klines_requires_dsn_or_env(monkeypatch):
    monkeypatch.delenv("WORKER_PG_READONLY_DSN", raising=False)
    with pytest.raises(RuntimeError):
        load_klines("BINANCE", "BTC/USDT", "1h", "s", "e")


def test_load_klines_uses_env_dsn(monkeypatch):
    factory, _, cursor = _fake_connector_returning([])
    monkeypatch.setenv("WORKER_PG_READONLY_DSN", "postgres://ro@pg/x")
    load_klines("BINANCE", "BTC/USDT", "1h", "2024-01-01", "2024-01-02",
                connector=factory)
    factory.assert_called_once_with("postgres://ro@pg/x")
    cursor.execute.assert_called_once()
    sql, params = cursor.execute.call_args.args
    assert "FROM klines" in sql and "SELECT" in sql
    assert params[0] == "BINANCE" and params[1] == "BTC/USDT"


def test_load_klines_maps_rows_to_dicts_and_iso_timestamps():
    ts = dt.datetime(2024, 1, 1, 8, 0)
    factory, conn, cursor = _fake_connector_returning([
        (ts, "42100", "42200", "42050", "42150", "123"),
        ("2024-01-01T09:00:00Z", "42150", "42300", "42100", "42250", "150"),
    ])
    result = load_klines("BINANCE", "BTC/USDT", "1h", "2024-01-01", "2024-01-02",
                        dsn="pg://x", connector=factory)
    assert len(result) == 2
    assert result[0]["timestamp"] == ts.isoformat()
    assert result[0]["open"] == "42100"
    assert result[1]["timestamp"] == "2024-01-01T09:00:00Z"
    # 关闭资源
    conn.close.assert_called_once()
    cursor.close.assert_called_once()


def test_load_klines_dsn_overrides_env(monkeypatch):
    factory, _, _ = _fake_connector_returning([])
    monkeypatch.setenv("WORKER_PG_READONLY_DSN", "postgres://env/x")
    load_klines("BINANCE", "BTC/USDT", "1h", "s", "e",
                dsn="postgres://override/y", connector=factory)
    factory.assert_called_once_with("postgres://override/y")


def test_load_klines_close_on_error():
    factory, conn, cursor = _fake_connector_returning([])
    cursor.execute.side_effect = RuntimeError("db down")
    with pytest.raises(RuntimeError):
        load_klines("BINANCE", "BTC/USDT", "1h", "s", "e",
                    dsn="pg://x", connector=factory)
    conn.close.assert_called_once()
    cursor.close.assert_called_once()
