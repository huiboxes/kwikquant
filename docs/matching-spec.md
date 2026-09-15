# 撮合与接受性规范（Matching & Acceptance Spec）

> **单一真相源**。回测与模拟盘的撮合语义（§1–§8）与订单接受性语义（§9）以本文档为准；
> 实现——Java `MatchingKernel`（`trading/domain`，模拟盘 PaperExecutor 用）、Java
> `OrderAcceptance`（`shared/types`，Order.validate 与回测预检共用）、Python
> `kwikquant_worker/backtest/matching.py` 与 `kwikquant_worker/acceptance.py`（回测 event
> loop 本地撮合/接受性用）——都是本规范的实现。语义漂移由差分对拍 fixtures 拦截（见 §8/§9）。
> 改撮合或接受性语义必须：先改本文档 → 再改 fixtures → 再改两侧实现，CI 双门控
> （JUnit `MatchingKernelFixturesTest` + pytest `test_matching_fixtures.py`）。

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
  事件回调（`on_fill`/`on_liquidation`/`on_funding`，契约见 `docs/strategy-api.md` §8）中下的单
  进**同一意图队列**、同 NEXT_BAR 语义（归属 bar 处理包内派发的回调，其意图最早在下一根 bar 撮合）。
  引擎时间轴是 BAR/FUNDING 节点归并的事件流（PERP 编排见 `docs/perp-backtest-spec.md` §6），
  撮合内核本身不感知节点类型——只消费"上一节点排队的意图 × 本 bar 快照"。
- **接受性闸门**（撮合前）：`pairSpecs` 快照可得时先过 §9 `acceptance.check`，拒单进 warnings
  （不再静默）；快照缺失该 symbol 时 SPOT 保持存量行为（跳过），PERP fail-closed 全拒。
- **账本闸门**（撮合成功后、应用前，对应原 Java 回测账本 canApply）：
  - SPOT BUY：`cash ≥ price×qty + fee`，否则拒单（警告，继续回测）；
  - SPOT SELL：`持仓 qty ≥ 成交 qty`，否则拒单（警告，继续回测）。
    **dust 容差**：`持仓 qty > 0` 且 `0 < 成交 qty − 持仓 qty < 1e-12` 时视为全平意图，
    成交量 clamp 到账本持仓原值并按 clamp 后 qty 重撮合（fee 同步重算）——消灭 Decimal
    运算残差（如 `100/3` 的 28 位商再舍入）造成的"满仓平不掉"假拒单。全平的正确姿势仍是
    `ctx.close_position()`（账本原值下单，从根上无残差）；
  - PERP：保证金闸门与净持仓应用规则见 `docs/perp-backtest-spec.md` §3/§6（无 dust 容差，
    CLOSE 超仓即拒——`close_position()` 用账本原值，正常路径无残差来源）。
  - 拒单不是错误：与原 7302 语义一致，记录 warning（上限 10 条）后继续。
- PERP 回测（净持仓账本、bar 极值强平近似、资金费事件回放）语义在 `docs/perp-backtest-spec.md`；
  组合（多标的）回测 SPOT 与 PERP 均支持（组合 PERP 见 spec §10，撮合内核与本规范同一）。

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
    全量 fixtures 过 `MatchingKernel.match`；`kind="acceptance"` 的 fixtures 过
    `OrderAcceptance.check`（§9）。
  - pytest `tests/python/test_matching_fixtures.py`：FAST fixtures 过 Python 引擎并断言逐字段相等；
    SPREAD/DEPTH fixtures 断言抛 `ValueError`（回测引擎适用范围）；`kind="acceptance"` 的
    fixtures 过 `kwikquant_worker/acceptance.py`。
- 对拍字段：`price / qty / fee / feeCurrency / liquidity`（+ 是否成交）。`externalFillId` 随机不参与。

## 9. 订单接受性（Order Acceptance）

与撮合**并列的独立纯函数层**：判定一笔订单在给定交易对规格下是否可接受（accept/reject 改变
可观察结果——成交 vs 拒单，与撮合同级）。**不塞进 `MatchingKernel.match`**（Java 侧接受性历史上
在 `Order.validate`，塞进撮合内核破坏两侧对称）。实现：

| 侧 | 实现 | 消费方 |
|---|---|---|
| Java | `shared/types/OrderAcceptance.check(input, pairSpec)` → `AcceptResult{ok, reasonCode, message}` | `Order.validate`（拒时抛 `InvalidOrderException(message)`，消息语义与本节逐字一致）；回测任务 pairSpecs 快照下发 |
| Python | `kwikquant_worker/acceptance.py` `check(input, pair_spec)` → `AcceptResult(ok, reason_code, message)` | 回测 `place_order` / 撮合前闸门（拒单 → warning，不再静默） |

### 9.1 输入

- **`AcceptInput`**：`symbol / marketType / side / orderType / amount / price / stopPrice /
  leverage / marginMode / positionEffect`。不含时间相关字段——**依赖墙钟的校验（GTD expireAt
  必须在未来）不进接受性层**（fixtures 不可复现），留在 Java `Order.validate`。
- **`PairSpec`**：`symbol / marketType / minQty / maxQty / tickSize / stepSize / maxLeverage`，
  全部**币单位**（PERP 的 minQty/stepSize 已在装载时币化，见 `TradingPairInfo` 单位契约）；
  `maxLeverage` 仅 PERP 有值。金额字段十进制精确数（JSON 字符串传输，禁 float）。
  `pairSpec = null` 表示交易对未知（fail-closed 拒）。

### 9.2 规则表（顺序敏感，命中即终止）

| # | 条件 | reasonCode | message（逐字，`{x}` = 输入值原样字符串化） |
|---|---|---|---|
| 1 | `symbol` null/blank | `SYMBOL_BLANK` | `symbol is blank` |
| 2 | `orderType` null | `ORDER_TYPE_REQUIRED` | `orderType is required` |
| 3 | `amount` null 或 ≤ 0 | `AMOUNT_POSITIVE` | `amount must be positive` |
| 4 | `pairSpec` null | `UNKNOWN_SYMBOL` | `unknown symbol: {symbol}` |
| 5 | `minQty` 非 null 且 `amount < minQty` | `MIN_QTY` | `amount {amount} < minQty {minQty}` |
| 6 | `maxQty` 非 null 且 `amount > maxQty` | `MAX_QTY` | `amount {amount} > maxQty {maxQty}` |
| 7 | `stepSize` 非 null 且 > 0 且 `amount mod stepSize ≠ 0` | `STEP_SIZE` | `amount {amount} not aligned to stepSize {stepSize}` |
| 8 | orderType ∈ {LIMIT, STOP_LIMIT, TAKE_PROFIT_LIMIT} 且 `price` null 或 ≤ 0 | `PRICE_REQUIRED` | `price required for {orderType}` |
| 9 | orderType ∈ {STOP_MARKET, STOP_LIMIT, TAKE_PROFIT_MARKET, TAKE_PROFIT_LIMIT} 且 `stopPrice` null 或 ≤ 0 | `STOP_PRICE_REQUIRED` | `stopPrice required for {orderType}` |
| 10 | `price` 非 null 且 `tickSize` 非 null > 0 且 `price mod tickSize ≠ 0` | `TICK_SIZE` | `price {price} not aligned to tickSize {tickSize}` |
| 11 | `stopPrice` 非 null 且 `tickSize` 非 null > 0 且 `stopPrice mod tickSize ≠ 0` | `TICK_SIZE` | `stopPrice {stopPrice} not aligned to tickSize {tickSize}` |
| 12 | PERP：`leverage` null 或 < 1 或 > 100 | `LEVERAGE_RANGE` | `PERP leverage must be 1-100, got: {leverage}` |
| 13 | PERP：`marginMode` null | `MARGIN_MODE_REQUIRED` | `PERP marginMode required (ISOLATED/CROSS)` |
| 14 | PERP：`positionEffect` null | `POSITION_EFFECT_REQUIRED` | `PERP positionEffect required (OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT)` |
| 15 | PERP：`side` 非 null 且 ≠ `positionEffect.toSide()` | `SIDE_EFFECT_MISMATCH` | `side {side} contradicts positionEffect {positionEffect} (expected side {derivedSide})` |
| 16 | PERP：`pairSpec.maxLeverage` null | `MAX_LEVERAGE_UNDECLARED` | `PERP maxLeverage not declared for {symbol}, refusing (fail-closed)` |
| 17 | PERP：`leverage > pairSpec.maxLeverage` | `MAX_LEVERAGE` | `leverage {leverage} exceeds maxLeverage {maxLeverage} for {symbol}` |
| 18 | SPOT：`side` null | `SIDE_REQUIRED` | `side is required` |
| 19 | SPOT：`leverage`/`marginMode`/`positionEffect` 任一非 null | `SPOT_CONTRACT_FIELDS` | `SPOT order must not set leverage/marginMode/positionEffect` |

全部通过 → `ok=true, reasonCode=null, message=null`。

补充语义：

- **PERP `side` 可 null**：side 单源 = `positionEffect.toSide()` 派生（#15 只在显式传入时校验矛盾）。
- `TRAILING_STOP` 不要求 price/stopPrice（历史行为，镜像不改：#8/#9 枚举均不含它）。
- `mod` = 十进制 remainder（Java `BigDecimal.remainder` / Python `Decimal.__mod__`），只判 `signum ≠ 0`，
  符号语义两侧一致。
- 数值字符串化 = 输入字面量原样（Java `BigDecimal.toString()` / Python `str(Decimal)`，两侧对同一
  JSON 字符串输入输出一致）；**message 中不得出现算术派生值**。
- 全局杠杆上限 100（#12）是常量，单源在 Java `OrderAcceptance.MAX_LEVERAGE_CAP`（`Order.validate`
  委托后不再持有该常量），Python `acceptance.MAX_LEVERAGE_CAP` 镜像；per-symbol 真实上限走 #16/#17。

### 9.3 差分对拍（acceptance fixtures）

- 位置：与撮合 fixtures 同目录 `tests/fixtures/matching/*.json`，以 `"kind": "acceptance"` 区分
  （缺省 kind = 撮合用例，存量 28 个不变）。
- Schema：

```json
{
  "name": "acceptance_perp_min_qty_rejected",
  "kind": "acceptance",
  "description": "PERP 开多数量低于币化 minQty → MIN_QTY 拒",
  "input": {
    "symbol": "BTC/USDT", "marketType": "PERP", "side": null, "orderType": "MARKET",
    "amount": "0.0001", "price": null, "stopPrice": null,
    "leverage": 10, "marginMode": "ISOLATED", "positionEffect": "OPEN_LONG"
  },
  "pairSpec": {
    "symbol": "BTC/USDT", "marketType": "PERP",
    "minQty": "0.001", "maxQty": null, "tickSize": "0.1", "stepSize": "0.001",
    "maxLeverage": 100
  },
  "expected": {
    "accepted": false, "reasonCode": "MIN_QTY", "message": "amount 0.0001 < minQty 0.001"
  }
}
```

  - `pairSpec: null` → 交易对未知用例（#4）。
  - 对拍字段：`accepted / reasonCode / message`（message 逐字，两侧字符串化规则见 §9.2）。
- 双门控：JUnit `MatchingKernelFixturesTest` 与 pytest `test_matching_fixtures.py` 按 `kind`
  dispatch 到各自接受性实现，断言三字段一致。
