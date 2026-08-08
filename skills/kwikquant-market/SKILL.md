---
name: kwikquant-market
description: |
  KwikQuant 行情数据查询工具。当用户需要查询加密货币 K线(OHLCV)历史、最新 ticker(最新价 / 买一卖一 / 24h 高低 / 成交量)、
  盘口深度(bids / asks)、或永续合约资金费率时使用。支持 binance / okx / bitget,spot / perp 两类市场。
  资金费率仅 PERP 有效。所有数据通过 CCXT 实时拉取,不入库。
---

# KwikQuant 行情数据

4 个工具,全部只读,通过 CCXT 实时拉取交易所行情。

## 通用入参

| 参数 | 取值 | 说明 |
|---|---|---|
| exchange | binance / okx / bitget | 交易所,小写 |
| marketType | spot / perp | spot=现货, perp=永续合约 |
| symbol | BTC/USDT 等 | CCXT 风格,含 `/` |
| interval | 1m / 5m / 15m / 1h / 4h / 1d | K线周期(仅 get_ohlcv) |
| start / end | ISO-8601 瞬时 | 如 `2024-01-01T00:00:00Z`(仅 get_ohlcv) |

非法枚举抛 10002。PAPER exchange 调实时行情抛 10002(PAPER 无实时行情)。

## 工具

### get_ohlcv
K线历史(OHLCV)。传 exchange / marketType / symbol / interval / start / end。

示例:
```
查 okx 永续 BTC/USDT 最近 1 周 1h K线
```

### get_ticker
最新 ticker:最新价 / 买一 / 卖一 / 24h 高低 / 成交量。传 exchange / marketType / symbol。

### get_orderbook
盘口深度,bids / asks 各 N 档。limit 可省,默认 20。exchange=PAPER 抛 10002。

### get_funding_rate
资金费率(仅 PERP)。返当前费率 / 标记价 / 下一轮费率及结算时间。SPOT 调用抛 10002。

## 典型场景

- **判断费率方向**:`get_funding_rate` → 正费率多头付费(LONG 付 SHORT),负费率反之
- **找入场点**:`get_ticker` 看最新价 + `get_orderbook` 看挂单墙 + `get_ohlcv` 看趋势
