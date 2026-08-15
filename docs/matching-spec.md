# 撮合语义规范（Matching Spec）

> **单一真相源**。回测与模拟盘的撮合语义以本文档为准；两个实现——Java
> `MatchingKernel`（`trading/domain`，模拟盘 PaperExecutor 用）与 Python
> `kwikquant_worker/backtest/matching.py`（回测 event loop 本地撮合用）——都是本规范的实现。
> 语义漂移由差分对拍 fixtures 拦截（见 §8）。改撮合语义必须：先改本文档 → 再改 fixtures →
> 再改两侧实现，CI 双门控（JUnit `MatchingKernelFixturesTest` + pytest `test_matching_fixtures.py`）。

## 1. 适用范围

| 场景 | 实现 | fidelity |
|---|---|---|
| 回测（backtest） | Python `backtest/matching.py` | 永远 **FAST**（仅有 K 线数据） |
| 模拟盘（paper） | Java `MatchingKernel` | FAST / SPREAD / DEPTH（按配置） |
| 实盘（live） | 交易所撮合（CCXT） | 不适用 |

Python 回测引擎**只实现 FAST**：SPREAD/DEPTH 需要 ticker bid/ask 与 L2 orderbook，回测没有这些数据。
SPREAD/DEPTH fixtures 因此是 Java-only（pytest 侧断言回测引擎对其抛 `ValueError`，显式记录适用范围）。

## 2. 输入

- **订单**：`side`（BUY/SELL）、`orderType`、`amount`（数量，>0）、`price`（限价，LIMIT 必填）。
  回测中订单是"意图"（无状态机）；模拟盘中是带状态机的 `Order`。
- **市场快照**：统一结构（Java `MarketSnapshot` / Python snapshot dict），字段
  `timestamp / last / bid / ask / open / high / low / close / volume / bids / asks`。
  FAST 只用 `last/high/low`；SPREAD 用 `last/bid/ask`；DEPTH 再加 `bids/asks`。不适用的字段为 null/空。
  金额字段一律十进制精确数（Java `BigDecimal` / Python `Decimal`，JSON 中以字符串传输，**禁止绕道 float**）。
- **撮合配置 `MatchConfig`**：`fidelity`、`marketSlippageBps`、`partialFillEnabled`、`makerFeeRate`、`takerFeeRate`。

### 默认配置（两侧实现必须一致）

| 字段 | 默认值 | 说明 |
|---|---|---|
| fidelity | `FAST` | |
| marketSlippageBps | `5` | 市价单滑点，basis points（5 = 0.05%） |
| partialFillEnabled | `false` | v1 不模拟部分成交，满足条件即全成 |
| makerFeeRate | `0.001` | 限价成交费率 |
| takerFeeRate | `0.002` | 市价成交费率 |

Java 源：`MatchConfig.defaults()`；Python 源：`MatchConfig.defaults()`（`backtest/matching.py`）；
回测任务下发快照：`BacktestExecutionGateway.defaultMatchingConfig()`。三处必须同步，本文档是仲裁依据。

## 3. 前置门槛（不撮合的情形）

按顺序判定，命中即返回"无成交"：

1. **终态订单**（模拟盘状态机）：订单已终态（FILLED/CANCELLED/REJECTED/EXPIRED）→ 无成交。
   回测意图订单无状态机，此条不适用。
2. **剩余数量 ≤ 0**：`amount ≤ 0`（模拟盘为 remainingQty）→ 无成交。
3. **条件单不主动触发**：`STOP_MARKET / STOP_LIMIT / TAKE_PROFIT_MARKET / TAKE_PROFIT_LIMIT / TRAILING_STOP`
   → 无成交（内核不监听触发价；实盘走交易所条件单，模拟盘由策略自行观察价格后下普通单）。
4. 类型分派：`MARKET` → §4，`LIMIT` → §5。

## 4. MARKET 成交价规则

### FAST（last ± 固定滑点）

```
sign       = +1 (BUY) / -1 (SELL)
factor     = marketSlippageBps / 10000      # 先舍入：scale=8, HALF_UP
fillPrice  = last × (1 + sign × factor)
```

- `last` 为 null → 无成交。
- `fillPrice ≤ 0` → 无成交（例如 SELL 且滑点 ≥ 10000bps）。
- 滑点因子先按 8 位小数 HALF_UP 舍入，再参与乘法（两侧实现必须保持此顺序）。

### SPREAD（价差）

- BUY → `ask`；SELL → `bid`。对应价为 null → 无成交。

### DEPTH（orderbook walk-the-book VWAP）

- 买单走 `asks`，卖单走 `bids`；book 为空 → 无成交。
- 逐档吃量：`take = min(剩余, 档qty)`，累计 `cost += take × 档price`；吃完或量足即停。
- 流动性不足（book 总量 < 订单量）：剩余部分按**最后一档成交价**估算（保守，VWAP 不穿价）。
- `fillPrice = totalCost / amount`，除法 scale=8, HALF_UP。

MARKET 成交一律 **taker** 费率。

## 5. LIMIT 触发与成交价规则

`price` 为 null → 无成交。

### 触发判定

| fidelity | BUY 触发条件 | SELL 触发条件 |
|---|---|---|
| FAST | `low ≤ price`（low/high 任一为 null → 不触发） | `high ≥ price` |
| SPREAD / DEPTH | `trigger ≤ price` | `trigger ≥ price` |

SPREAD/DEPTH 的 `trigger`：BUY 取 `ask`、SELL 取 `bid`；为 null 时回退 `last`；仍为 null → 不触发。
FAST 用 K 线 low/high 判定"bar 内是否穿过限价"；边界相等（low == price）**算触发**。

### 成交价

触发后**按限价 price 全额成交**（不按触发价），**maker** 费率。

## 6. 成交（Fill）构造

| 字段 | 规则 |
|---|---|
| price | 成交价，scale=8, HALF_UP |
| qty | 全额（v1 无部分成交） |
| fee | `成交价(未舍入原值) × qty × 费率`，scale=8, HALF_UP |
| feeCurrency | symbol 中 `/` 之后的 quote 部分（如 `BTC/USDT` → `USDT`）；symbol 无合法 `/` → null。买卖统一 quote 计价 |
| liquidity | MARKET → `taker`；LIMIT → `maker` |
| filledAt | 快照 timestamp（快照无时间 → 当前时刻） |
| externalFillId | 随机，不参与差分对拍 |

**舍入顺序**：fee 用未舍入的原始成交价计算后再 8 位 HALF_UP；price 独立 8 位 HALF_UP。
中间乘法按精确值（Java BigDecimal 精确；Python Decimal 28 位有效数字对本文档量级的值精确），
只在 fee/price 最终落 8 位。

## 7. 回测集成语义（Python event loop）

- **NEXT_BAR**：bar i 的 `on_bar` 中下的单，最早在 bar i+1 用 bar i+1 的快照撮合（策略在上一根收盘后才看到完整 OHLC）。
- **账本闸门**（撮合成功后、应用前，对应原 Java 回测账本 canApply）：
  - BUY：`cash ≥ price×qty + fee`，否则拒单（警告，继续回测）；
  - SELL：`持仓 qty ≥ 成交 qty`，否则拒单（警告，继续回测）。
  - 拒单不是错误：与原 7302 语义一致，记录 warning（上限 10 条）后继续。
- 回测仅 SPOT（PERP 在 Gateway 入口即拒，Python 引擎不实现保证金/强平）。

## 8. 差分对拍（fixtures）

- 位置：`tests/fixtures/matching/*.json`，每文件一个用例。
- Schema：

```json
{
  "name": "fast_market_buy_slippage",
  "description": "FAST 市价买单：last × (1+5bps) 滑点 + taker 费",
  "config": { "fidelity": "FAST", "marketSlippageBps": "5", "partialFillEnabled": false,
              "makerFeeRate": "0.001", "takerFeeRate": "0.002" },
  "order":  { "symbol": "BTC/USDT", "side": "BUY", "orderType": "MARKET",
              "amount": "0.1", "price": null },
  "snapshot": { "timestamp": "2026-06-30T00:00:00Z", "last": "42000",
                "open": "42000", "high": "42000", "low": "42000", "close": "42000",
                "bid": null, "ask": null, "bids": [], "asks": [] },
  "expected": { "price": "42021.00000000", "qty": "0.1", "fee": "8.40420000",
                "feeCurrency": "USDT", "liquidity": "taker" }
}
```

  - `order.status`（默认 `SUBMITTED`）/ `order.filledQty`（默认 `"0"`）：仅 Java 侧状态机用，
    构造终态/零剩余用例。
  - `expected: null` → 断言无成交。
  - `config.fidelity ∈ {SPREAD, DEPTH}` 的用例为 **Java-only**（见 §1）。
- 跑法：
  - JUnit `MatchingKernelFixturesTest`（`src/test/java/com/kwikquant/trading/domain/`）：
    全量 fixtures 过 `MatchingKernel.match`。
  - pytest `tests/python/test_matching_fixtures.py`：FAST fixtures 过 Python 引擎并断言逐字段相等；
    SPREAD/DEPTH fixtures 断言抛 `ValueError`（回测引擎适用范围）。
- 对拍字段：`price / qty / fee / feeCurrency / liquidity`（+ 是否成交）。`externalFillId` 随机不参与。
