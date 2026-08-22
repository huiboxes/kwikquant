---
name: kwikquant-market
description: |
  KwikQuant 行情数据查询工具。当用户需要查询加密货币 K线(OHLCV)历史、最新 ticker(最新价 / 买一卖一 / 24h 高低 / 成交量)、
  盘口深度(bids / asks)、或永续合约资金费率时使用。支持 binance / okx / bitget,spot / perp 两类市场。
  资金费率仅 PERP 有效。数据源:K线走 CCXT(API-first,同参 2h 内存缓存);ticker 读 worker 缓存/库内最新快照;盘口/资金费率 CCXT 实时拉取不持久化。
---

# KwikQuant 行情数据

4 个工具,全部只读。数据源:K线走 CCXT(API-first,同参 2h 内存缓存);ticker 读 worker 缓存/库内最新快照;盘口/资金费率 CCXT 实时拉取不持久化。

## 通用入参

| 参数 | 取值 | 说明 |
|---|---|---|
| exchange | binance / okx / bitget | 交易所,小写 |
| marketType | spot / perp | spot=现货, perp=永续合约 |
| symbol | BTC/USDT 等 | CCXT 风格,含 `/` |
| interval | 1m / 5m / 15m / 1h / 4h / 1d | K线周期(仅 get_ohlcv) |
| start / end | ISO-8601 瞬时 | 如 `2024-01-01T00:00:00Z`(仅 get_ohlcv) |

非法枚举抛 10002。PAPER exchange 调实时行情抛 10002(PAPER 无实时行情,用 okx / binance / bitget)。

## 工具

### get_ohlcv
K线历史(OHLCV)。传 `exchange / marketType / symbol / interval / start / end`。返每根 K 线 `openTime / open / high / low / close / volume`。

```
查 okx 永续 BTC/USDT 最近 1 周 1h K线
```
CLI 对应:`kwikquant kline BTC/USDT -m perp -p 1h --limit 168`

### get_ticker
最新 ticker:最新价 / 买一 / 卖一 / 24h 高低 / 成交量。传 `exchange / marketType / symbol`。返回 worker 缓存或库内最新快照(无实时性标记字段)。

```
查 okx 现货 BTC/USDT 最新价
```
CLI 对应:`kwikquant quote BTC/USDT -m spot`

### get_orderbook
盘口深度,`bids / asks` 各 N 档(每档 `price / amount`)。`limit` 可省,默认 20。`exchange=PAPER` 抛 10002。

```
查 binance 现货 ETH/USDT 盘口 20 档
```
CLI 对应:`kwikquant depth ETH/USDT -d 20`

### get_funding_rate
资金费率(仅 PERP)。返当前费率 / 标记价 / 下一轮费率及结算时间。SPOT 调用抛 10002。

```
查 okx 永续 BTC/USDT 当前资金费率
```
CLI 对应:CLI 暂无直接命令;资金费结算历史查 `get_funding_history`(见 [kwikquant-trading](../kwikquant-trading/SKILL.md))。

## 返回字段

详细字段见 [REST API 参考](../../docs/api-reference.md)(market 段)。

| 工具 | 关键返回字段 |
|---|---|
| get_ohlcv | openTime, open, high, low, close, volume |
| get_ticker | last, bid, ask, high, low, open, baseVolume, quoteVolume, change, percentage, timestamp |
| get_orderbook | bids[], asks[](price, amount) |
| get_funding_rate | fundingRate, markPrice, nextFundingRate, nextFundingTime |

金额红线:价格 / 成交量 / 费率等数值字段一律 decimal string(见总入口 [kwikquant](../kwikquant/SKILL.md))。

## 错误码

| code | 含义 |
|---|---|
| 10002 | 枚举非法(exchange 非 binance/okx/bitget / marketType 非 spot|perp / PAPER 调实时行情 / SPOT 调资金费率) |
| 6001 | 交易所 API 失败(限频 / 网络 / 代理,见 .env CCXT_PROXY) |

## 典型场景

- **判断费率方向**:`get_funding_rate` → 正费率多头付费(LONG 付 SHORT),负费率反之
- **找入场点**:`get_ticker` 看最新价 + `get_orderbook` 看挂单墙 + `get_ohlcv` 看趋势
- **多 symbol 批量**:CLI `kwikquant quote BTC/USDT ETH/USDT`(MCP 一次一 symbol,多 symbol 须多次调用)
