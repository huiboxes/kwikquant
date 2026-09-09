"""data_loader — 调 Java REST 拉 K 线(回测数据获取重构,废 PG 直连)。"""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from kwikquant_worker.data_loader import load_klines


def _kline(t: str = "2024-01-01T00:00:00Z") -> dict:
    return {
        "timestamp": t,
        "open": "50000",
        "high": "50100",
        "low": "49900",
        "close": "50050",
        "volume": "12.5",
    }


def test_load_klines_calls_java_rest():
    client = MagicMock()
    client.trade.get_klines.return_value = [_kline()]

    result = load_klines(
        client,
        42,
        exchange="OKX",
        market_type="SPOT",
        symbol="BTC/USDT",
        interval="1h",
        start="2024-01-01T00:00:00Z",
        end="2024-01-02T00:00:00Z",
    )

    assert len(result) == 1
    assert result[0]["timestamp"] == "2024-01-01T00:00:00Z"
    client.trade.get_klines.assert_called_once_with(
        42,
        exchange="OKX",
        market_type="SPOT",
        symbol="BTC/USDT",
        interval="1h",
        start="2024-01-01T00:00:00Z",
        end="2024-01-02T00:00:00Z",
    )


def test_load_klines_empty_returns_empty():
    # 空结果不抛(上层 worker_server 据此 exit 2 → Java markFailed 7304)
    client = MagicMock()
    client.trade.get_klines.return_value = []

    result = load_klines(
        client, 1, exchange="OKX", market_type="SPOT",
        symbol="BTC/USDT", interval="1h", start="s", end="e",
    )

    assert result == []


def test_load_klines_propagates_client_error():
    # REST 网络错/4xx 透传抛(上层 exit 1)
    client = MagicMock()
    client.trade.get_klines.side_effect = RuntimeError("network down")

    with pytest.raises(RuntimeError):
        load_klines(
            client, 1, exchange="OKX", market_type="SPOT",
            symbol="BTC/USDT", interval="1h", start="s", end="e",
        )


# ---------------- PERP 资金费序列(load_funding_rates / find_funding_gaps) ----------------

from datetime import datetime, timezone  # noqa: E402
from decimal import Decimal  # noqa: E402

from kwikquant_worker.backtest.perp_ledger import FundingPeriod  # noqa: E402
from kwikquant_worker.data_loader import (  # noqa: E402
    FundingDataMissingError,
    find_funding_gaps,
    load_funding_rates,
)

NOW = datetime(2024, 1, 10, 0, 0, tzinfo=timezone.utc)


def _row(t: str, rate: str = "0.0001", interval: int | None = 28800, source: str = "EXCHANGE") -> dict:
    return {
        "funding_time": t,
        "settled_rate": rate,
        "interval_seconds": interval,
        "mark_price": "60000",
        "source": source,
    }


def _period(t: str, interval: int | None = 28800) -> FundingPeriod:
    from kwikquant_worker.backtest.perp_ledger import parse_instant

    return FundingPeriod(
        funding_time=parse_instant(t), settled_rate=Decimal("0.0001"),
        interval_seconds=interval, mark_price=None,
    )


class TestLoadFundingRates:
    def test_maps_rows_and_queries_with_24h_buffer(self):
        client = MagicMock()
        client.trade.get_funding_rates.return_value = [
            _row("2024-01-01T00:00:00Z"),
            _row("2024-01-01T08:00:00Z", rate="-0.0002", source="PROXY_BINANCE"),
            _row("2024-01-01T16:00:00Z"),
        ]

        periods = load_funding_rates(
            client, 42, exchange="OKX", symbol="BTC/USDT",
            start="2024-01-01T00:00:00Z", end="2024-01-02T00:00:00Z",
        )

        assert len(periods) == 3
        assert periods[0].settled_rate == Decimal("0.0001")
        assert periods[1].settled_rate == Decimal("-0.0002")
        assert periods[1].source == "PROXY_BINANCE"
        assert periods[0].mark_price == Decimal("60000")
        # 查询终点 = 任务 end + 24h 前瞻缓冲(末根 bar 期次可落在任务 end 之后)
        call = client.trade.get_funding_rates.call_args
        assert call.kwargs["end"] == "2024-01-03T00:00:00Z"
        assert call.kwargs["start"] == "2024-01-01T00:00:00Z"

    def test_filters_rows_without_settled_rate(self):
        # predicted-only 行防御性过滤(Java 端点只返 settled 行)。被滤行放区间外:
        # 区间内的 settled 缺行会正确判缺期(该期未结算 = 数据不完整,fail-closed 语义)
        client = MagicMock()
        client.trade.get_funding_rates.return_value = [
            _row("2024-01-01T00:00:00Z"),
            _row("2024-01-01T08:00:00Z"),
            _row("2024-01-01T16:00:00Z"),
            _row("2024-01-02T00:00:00Z"),
            {"funding_time": "2024-01-02T08:00:00Z", "settled_rate": None},
        ]

        periods = load_funding_rates(
            client, 42, exchange="OKX", symbol="BTC/USDT",
            start="2024-01-01T00:00:00Z", end="2024-01-02T00:00:00Z",
        )
        assert len(periods) == 4  # 01-02T08:00 predicted-only 被滤
        assert all(p.settled_rate is not None for p in periods)

    def test_buffer_zone_hole_not_detected(self):
        # (end, end+24h) 前瞻缓冲区内的洞(01-02T08:00 缺,00:00→16:00 间隔 16h > 1.5×8h)
        # 不影响任务区间完整性——检测已收敛到 [start, end],不再假阳性 exit 3;
        # 缓冲行仍原样返回(末根 bar 期次归属消费)
        client = MagicMock()
        client.trade.get_funding_rates.return_value = [
            _row("2024-01-01T00:00:00Z"),
            _row("2024-01-01T08:00:00Z"),
            _row("2024-01-01T16:00:00Z"),
            _row("2024-01-02T00:00:00Z"),
            _row("2024-01-02T16:00:00Z"),
        ]

        periods = load_funding_rates(
            client, 42, exchange="OKX", symbol="BTC/USDT",
            start="2024-01-01T00:00:00Z", end="2024-01-02T00:00:00Z",
        )
        assert len(periods) == 5

    def test_gaps_raise_funding_data_missing(self):
        client = MagicMock()
        client.trade.get_funding_rates.return_value = [_row("2024-01-01T00:00:00Z")]

        with pytest.raises(FundingDataMissingError, match="缺期"):
            load_funding_rates(
                client, 42, exchange="OKX", symbol="BTC/USDT",
                start="2024-01-01T00:00:00Z", end="2024-01-02T00:00:00Z",
            )

    def test_empty_series_raises(self):
        client = MagicMock()
        client.trade.get_funding_rates.return_value = []

        with pytest.raises(FundingDataMissingError, match="序列为空"):
            load_funding_rates(
                client, 42, exchange="OKX", symbol="BTC/USDT",
                start="2024-01-01T00:00:00Z", end="2024-01-02T00:00:00Z",
            )


class TestFindFundingGaps:
    START = "2024-01-01T00:00:00Z"
    END = "2024-01-02T00:00:00Z"

    def test_complete_series_no_gaps(self):
        periods = [
            _period("2024-01-01T00:00:00Z"),
            _period("2024-01-01T08:00:00Z"),
            _period("2024-01-01T16:00:00Z"),
            _period("2024-01-02T00:00:00Z"),
        ]
        assert find_funding_gaps(periods, start=self.START, end=self.END, now=NOW) == []

    def test_empty(self):
        assert find_funding_gaps([], start=self.START, end=self.END, now=NOW) == [
            "序列为空(区间内无任何已结算期次)"
        ]

    def test_head_gap(self):
        periods = [_period("2024-01-01T16:00:00Z"), _period("2024-01-02T00:00:00Z")]
        gaps = find_funding_gaps(periods, start=self.START, end=self.END, now=NOW)
        assert any(g.startswith("头缺") for g in gaps)

    def test_middle_gap(self):
        periods = [
            _period("2024-01-01T00:00:00Z"),
            _period("2024-01-01T08:00:00Z"),
            # 16:00 缺
            _period("2024-01-02T00:00:00Z"),
        ]
        gaps = find_funding_gaps(periods, start=self.START, end=self.END, now=NOW)
        assert any(g.startswith("缺期") for g in gaps)

    def test_interval_switch_not_false_positive(self):
        # 期次周期历史切换 8h→4h:切换点间隔 4h ≤ max(8h,4h)×1.5,不误报
        periods = [
            _period("2024-01-01T00:00:00Z"),
            _period("2024-01-01T08:00:00Z"),
            _period("2024-01-01T12:00:00Z", interval=14400),
            _period("2024-01-01T16:00:00Z", interval=14400),
            _period("2024-01-01T20:00:00Z", interval=14400),
            _period("2024-01-02T00:00:00Z", interval=14400),
        ]
        assert find_funding_gaps(periods, start=self.START, end=self.END, now=NOW) == []

    def test_tail_gap(self):
        # 末期 08:00,任务 end 次日 00:00(now 远未来,grace 不生效)→ 尾缺
        periods = [_period("2024-01-01T00:00:00Z"), _period("2024-01-01T08:00:00Z")]
        gaps = find_funding_gaps(periods, start=self.START, end=self.END, now=NOW)
        assert any(g.startswith("尾缺") for g in gaps)

    def test_tail_grace_when_end_near_now(self):
        # end 贴近 now:最新期次可能尚未结算,尾缺基准 = min(end, now−interval) 防误报
        now = datetime(2024, 1, 2, 2, 0, tzinfo=timezone.utc)  # grace = 01-01T18:00
        periods = [
            _period("2024-01-01T00:00:00Z"),
            _period("2024-01-01T08:00:00Z"),
            _period("2024-01-01T16:00:00Z"),
        ]
        gaps = find_funding_gaps(periods, start=self.START, end=self.END, now=now)
        assert not any(g.startswith("尾缺") for g in gaps)

    def test_none_interval_rows_skip_gap_check(self):
        # interval 未声明的行不参与间隔检测(Java 预检已 fail-closed,此处防御)
        periods = [
            _period("2024-01-01T00:00:00Z", interval=None),
            _period("2024-01-01T08:00:00Z", interval=None),
        ]
        assert find_funding_gaps(periods, start=self.START, end=self.END, now=NOW) == []
