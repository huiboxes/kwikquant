# PERP 数学内核规范（Perp Math Spec）

> **单一真相源**。PERP 纯数学语义以本文档为准；两个实现——Java
> `PerpMath`（`shared/types`，paper/live 交易链路用）与 Python
> `kwikquant_worker/perp_math.py`（回测引擎用）——都是本规范的实现。
> 语义漂移由差分对拍 fixtures 拦截（见 §5）。改任何函数的运算顺序、舍入点或校验，必须：
> 先改本文档 → 再改 fixtures → 再改两侧实现，CI 双门控
> （JUnit `PerpMathFixturesTest` + pytest `test_perp_math_fixtures.py`）。

## 1. 适用范围与消费方

内核只做**纯数学**：无状态、无 Spring、无聚合记账。持仓字段簿记（side/positionSide、
全平清理、CAS、事务）属于调用方（Java `PositionService` / Python 回测账本，后者随回测
PERP 批次接入）。四向 `PositionEffect` → `(positionSide, open)` 的映射也属于调用方
（Java 单源在 `PositionEffect.toPositionSide()`），内核只认 `LONG`/`SHORT` 桶 + 开/平标志。

| 函数 | Java 消费方 | Python 消费方 |
|---|---|---|
| toContracts / toCoin | `DefaultCcxtOrderAdapter`（张↔币边界） | 回测无交易所边界（不使用，仅对拍守护） |
| signedDelta | —（回测净持仓模式预留） | 回测 PERP 账本（净持仓 signed qty） |
| initialMargin | `TradingTransactionHelper.freezePerpMargin`、`applyPositionDelta` | 回测 PERP 保证金 |
| maintenanceMarginRequired | `CrossLiquidationChecker`、`PaperExecutor`（ISOLATED 单仓） | 回测 CROSS/ISOLATED 强平 |
| liquidationPriceIsolated | `Position.computeLiquidationPrice`（展示/参考价，写 positions.liquidation_price） | 回测 ISOLATED 强平价 |
| marginBreached | `PaperExecutor`（ISOLATED 单仓）、`CrossLiquidationChecker`（CROSS 账户级） | 回测强平触发 |
| fundingAmount | `PaperFundingSettlementScheduler` | 回测资金费结算 |
| closedPnl / weightedAvgEntryPrice / frozenMarginRelease | `applyPositionDelta` 内部构件 | 同左 |
| applyPositionDelta | `PositionService.applyPerpDelta` | 回测 PERP 持仓增量 |

**单位口径**（批次 B 决策，先于本规范）：域内规范单位 = 币数量（base coin）；张数
（contracts）只存在于交易所边界适配器内部。本内核所有 qty/price/margin 输入输出均为币口径。

## 2. 定点约定

- 金额/数量一律十进制精确数：Java `BigDecimal`，Python `Decimal`；fixtures JSON 中以
  **字符串**传输，**禁止绕道 float**。`leverage` 是 int，`open` 是 bool。
- **精度包络**：Java 无限精度（`BigDecimal` 精确加减乘；除法显式指定 scale）。Python 所有
  运算在 `localcontext(prec=50, ROUND_HALF_UP)` 内执行，舍入点用显式 `quantize`。
  生产量级（price ≤ 1e8、qty ≤ 1e6、leverage ≤ 100、|rate| ≤ 1）下，Python「prec-50 中间值
  + quantize」与 Java「精确值 + setScale」不可能产生双重舍入分歧；fixtures 全部在包络内。
  超出包络的输入行为不保证跨语言逐位一致（文档声明，不视为 bug）。**精确整除不受包络约束**：
  Python 用无标度整数运算判定可除性并构造商（§3.1），不依赖 context 精度，任意量级与 Java 严格一致。
- **舍入模式**：一律 HALF_UP。两种语言的 HALF_UP 都是**远离零**（负数 −x.5 → −(x+1)），
  含负值输出（closedPnl、fundingAmount）时两侧一致。
- **输出两类**（每个函数在 §3 标注类别）：
  - **EXACT**：精确加减乘或精确整除的结果，**不额外舍入**（scale 由输入决定，加减取 max、
    乘取 sum，两语言表示规则一致）。
  - **SCALE_8**：运算链终点 `setScale(8, HALF_UP)` / `quantize(0.00000001, ROUND_HALF_UP)`。
- **最小舍入原则**：只在「除法必须定 scale」与「落账/发布定标」点舍入，中间量保持精确。
  这与内核收编前的 Java 消费点行为逐点一致（切换零行为差异），DB 列一律 NUMERIC(20,8)
  在持久化层兜底定标。
- 默认维持保证金率 `DEFAULT_MAINT_MARGIN_RATE = 0.005`（近似 OKX 最低档；实际随档位变化）
  单源在 `PerpMath`，`Position.DEFAULT_MAINT_MARGIN_RATE` 是别名。

## 3. 函数规范

校验失败一律抛异常（错误码表见 §4）；校验在运算前执行，顺序不影响结果。
`requirePositive(x, name)`：x 为 null 或 ≤ 0 拒绝；`requireNonNegative`：null 或 < 0 拒绝。

### 3.1 toContracts（EXACT）

币数量 → 张数（出站下单边界）。

```
sz = coinAmount / contractSize        # 精确整除，无 scale
```

- 校验：coinAmount > 0，contractSize > 0。
- **除不尽即抛**（Java `BigDecimal.divide` 原生 `ArithmeticException`；Python 用无标度整数做
  **整数可除性判定**：a/b 约分后分母含 2/5 以外质因子即非终止，抛；可除则整数运算构造精确商。
  **禁止用「prec 内除完回乘验证 q × b == a」**——商第 51 位向上舍入时 q × b 会在 prec-50 里被舍回
  a，约半数非终止输入漏报，fail-closed 变 fail-open）——静默取整会让实际下单量偏离用户意图。
  amount 已按币化 stepSize（= lotSz × contractSize）对齐时天然整除。

### 3.2 toCoin（EXACT）

张数 → 币数量（回流解析边界）。

```
coin = contracts × contractSize       # 乘法精确
```

- 校验：contracts ≥ 0（**允许 0**：OKX 对未成交订单恒返 fillSz="0"、双向持仓/平仓窗口可出现
  pos="0"，拒 0 会炸掉撤单/对账路径），contractSize > 0。
- 成交事件语义的「必须为正」由下游（`Order.accumulateFill`）把关，不在换算层重复。

### 3.3 signedDelta（EXACT）

方向化数量增量（净持仓模式账本用）。

```
BUY  → +qty
SELL → −qty
```

- 校验：qty > 0；side 非空（Java 为 `OrderSide` 枚举，类型系统天然拒非法值，null 检查消息
  不镜像 Python；Python 校验字符串 ∈ {BUY, SELL}）。本函数无 §4 错误码 fixtures。

### 3.4 initialMargin（SCALE_8）

开仓初始保证金（逐仓，不区分多空方向）。

```
initialMargin = (price × qty) / leverage      # 乘积精确，除法 scale=8 HALF_UP
```

- 校验：price > 0，qty > 0，leverage ≥ 1（int）。

### 3.5 maintenanceMarginRequired（EXACT）

单仓维持保证金（CROSS 账户级聚合的加数；ISOLATED 单仓触发判定直接用单仓值）。

```
maintMargin = markPrice × qty × maintMarginRate      # 全精确，不舍入
```

- 校验：markPrice > 0，qty > 0，maintMarginRate ∈ (0, 1)。
- 逐仓不舍入、聚合后一次比较（§3.7），避免逐仓 dust 累积；ISOLATED 单仓场景同一值
  直接进 §3.7 谓词。

### 3.6 liquidationPriceIsolated（SCALE_8）

逐仓简化强平价，**margin-aware**：保证金余额恰好跌到维持保证金时的标记价。输入是持仓
数量与保证金（不是杠杆）——保证金被资金费侵蚀后（margin ≠ avg×qty/leverage）杠杆式
公式失真，margin-aware 口径才与 §3.7 触发谓词一致。**运算顺序影响结果**，两侧必须保持：

```
notional = avgEntryPrice × qty                                  # 精确
LONG:  liquidationPrice = (notional − margin) / (qty × (1 − maintMarginRate))
SHORT: liquidationPrice = (notional + margin) / (qty × (1 + maintMarginRate))
# 分子/分母精确，除法 scale=8 HALF_UP——分母如 0.995=199/200 含 2/5 以外质因子，
# 除法普遍非终止，必须显式舍入
```

推导（LONG，SHORT 对称）：强平边界 = marginBalance(mark) ≤ maintReq(mark)，即
`margin + (mark − avg) × qty ≤ mark × qty × mmr`，解出 mark 即上式（qty > 0、mmr < 1
保证分母为正，不等号方向不变）。

- 校验：avgEntryPrice > 0，qty > 0，margin 非空（**可为负**：资金费可把仓位保证金侵蚀
  穿仓），maintMarginRate ∈ (0, 1)，positionSide ∈ {LONG, SHORT}。
- **结果可 ≤ 0**：LONG 保证金耗尽/穿蚀时强平价可为 0 或负——此时任何价格比较都不可能
  触发。强平**触发判定一律用 §3.7 marginBreached**（mark 派生 marginBalance 与 maintReq
  比较），本函数输出只作展示/参考价（positions.liquidation_price 列、对账 fallback）。
- 例（fixture 钉住）：avg=60000、qty=1、margin=6000、mmr=0.005、LONG →
  54000 / 0.995 = 54271.356783919597… → 54271.35678392。
- **近似声明**：简化公式与 OKX 实盘强平价有偏差（OKX 含档位 mmr、费用补偿、标记价指数等），
  PAPER/回测近似用，不声称与交易所等价。CROSS 无单仓强平价（调用方 guard，见
  `Position.computeLiquidationPrice` CROSS 返 null）。

### 3.7 marginBreached（谓词）

保证金穿仓触发谓词，CROSS 与 ISOLATED **共用**：CROSS 账户级（marginBalance =
paper free + Σ 各仓 unrealizedPnl，maintMarginRequired = Σ 各仓 §3.5）与 ISOLATED
单仓（marginBalance = 仓位保证金 + 该仓 unrealizedPnl，maintMarginRequired = §3.5 单仓）。

```
breached = marginBalance ≤ 0  OR  maintMarginRequired ≥ marginBalance
```

- 校验：marginBalance 非空（可为负），maintMarginRequired ≥ 0。
- 边界语义：相等即触发（≥）；余额非正即触发。
- ISOLATED 强平判定**必须**用本谓词，而不是 markPrice 与 positions.liquidation_price 的
  价格比较：资金费侵蚀后 LONG 强平价可 ≤ 0（§3.6），价格比较永不触发，仓位会被放血
  至穿仓而不强平。

### 3.8 fundingAmount（SCALE_8）

单期资金费结算金额，**从持仓视角**带符号（正 = 收，入账加余额；负 = 付，扣余额）。

```
sideSign = SHORT ? +1 : −1
fundingAmount = fundingRate × markPrice × qty × sideSign     # 乘积精确，setScale(8, HALF_UP)
```

- 符号约定（OKX 语义）：正费率多头付空头收，负费率反转。
- 校验：positionSide ∈ {LONG, SHORT}，fundingRate 非空（**可为负、可为 0**，不校验范围），
  markPrice > 0，qty > 0。
- positionSide 非 LONG/SHORT（null/脏数据）→ 抛 INVALID_SIDE：调用方
  （`PaperFundingSettlementScheduler`）捕获后跳过该仓结算并 warn——**fail-closed**，
  不再默认按 LONG 方向结算（历史上默认 LONG 会把方向不明的仓位资金费算反）。
  同理，markPrice ≤ 0 / qty ≤ 0 的脏数据由内核校验拒绝 → 调度器跳过（内核收编前
  markPrice=0 会落一条 0 金额行、负 qty 会落符号错账行；现统一 fail-closed）。
- 期次网格（8h/interval、SETTLED vs 预估）不属于本内核，见资金费结算期次化设计。

### 3.9 closedPnl（EXACT）

平仓已实现盈亏（毛额，费用不入本函数）。

```
LONG:  (exitPrice − avgEntryPrice) × closeQty
SHORT: (avgEntryPrice − exitPrice) × closeQty
```

- 校验：positionSide ∈ {LONG, SHORT}，avgEntryPrice > 0，exitPrice > 0，closeQty > 0。
- 结果可为负（亏损）。

### 3.10 weightedAvgEntryPrice（SCALE_8）

加仓后的加权平均开仓价。

```
totalCost = oldAvgEntryPrice × oldQty + fillPrice × fillQty      # 精确
newAvg    = totalCost / (oldQty + fillQty)                       # scale=8, HALF_UP
```

- 校验：四个输入均 > 0（oldQty = 0 的建仓场景不走本函数，直接用 fillPrice，见 §3.12）。

### 3.11 frozenMarginRelease（分支定类）

平仓释放的冻结保证金。

```
closeQty == currentQty → 释放 currentFrozenMargin          # 全平精确释放（EXACT，免除法 dust）
closeQty <  currentQty → currentFrozenMargin × closeQty / currentQty   # scale=8 HALF_UP（SCALE_8）
```

- 校验：currentFrozenMargin ≥ 0，currentQty > 0，closeQty > 0，closeQty ≤ currentQty
  （超出抛 OVER_CLOSE）。
- 全平走精确分支保证释放后余额恰为 0，不留 0.00000001 级残差。

### 3.12 applyPositionDelta（组合，三段语义）

单向桶（LONG 或 SHORT）内叠加一笔成交。输入是当前桶状态 + 成交，输出
`PositionDelta(newQty, newAvgEntryPrice, realizedPnlDelta, marginDelta)`；
字段簿记（side 字符串、全平清理、强平价重算触发）由调用方完成。

```
校验（两分支共用）：positionSide ∈ {LONG, SHORT}，currentQty ≥ 0，currentFrozenMargin ≥ 0，
                    fillQty > 0，fillPrice > 0，leverage ≥ 1

open = true（开仓/加仓）:
    newQty             = currentQty + fillQty                       # EXACT
    newAvgEntryPrice   = currentQty == 0 ? fillPrice                # 建仓（EXACT）
                                         : weightedAvgEntryPrice(…) # 加仓（SCALE_8，§3.10）
    realizedPnlDelta   = 0
    marginDelta        = +initialMargin(fillPrice, fillQty, leverage)   # SCALE_8，冻结增加

open = false（平仓）:
    fillQty > currentQty → 抛 OVER_CLOSE
    realizedPnlDelta   = closedPnl(positionSide, currentAvgEntryPrice, fillPrice, fillQty)  # EXACT
    newQty             = currentQty − fillQty                       # EXACT
    marginDelta        = −frozenMarginRelease(currentFrozenMargin, currentQty, fillQty)     # §3.11
    newAvgEntryPrice   = newQty == 0 ? null : currentAvgEntryPrice  # 部分平不变；全平置 null
```

- 三段语义：**建仓/加仓**（open，currentQty==0 与 >0 两小段）、**部分减仓**（close，newQty>0）、
  **全平**（close，newQty==0，avg 置 null、marginDelta 恰为 −currentFrozenMargin）。
- currentAvgEntryPrice 仅在参与运算的分支校验（加仓 §3.10 要求 >0；平仓 §3.9 要求 >0；
  建仓不使用）。
- 无反手：反向意图由调用方拆成 CLOSE + OPEN 两笔（PERP 双向桶语义；净持仓模式的反手
  由回测账本基于 signedDelta 自行分解，不属于本函数）。
- 调用方持久化契约：`newFrozen = currentFrozenMargin + marginDelta`；OPEN 后重算
  liquidationPrice；全平后清理方向字段（Java 侧 `PositionService.applyPerpDelta`）。

## 4. 校验错误码（fixtures 语言中立表示）

fixture 的 `expected.error` 用下列代码；两侧 runner 各自映射到本语言异常类型 + 消息子串。
Python 一律抛 `ValueError`，消息逐字镜像 Java（便于跨语言 grep）。

| code | Java 异常 | 消息子串（两侧一致） |
|---|---|---|
| POSITIVE_REQUIRED | IllegalArgumentException | `must be positive` |
| NON_NEGATIVE_REQUIRED | IllegalArgumentException | `must be non-negative` |
| NON_TERMINATING_CONTRACTS | ArithmeticException（JDK 原生） | `Non-terminating decimal expansion` |
| OVER_CLOSE | IllegalArgumentException | `over-position` |
| INVALID_LEVERAGE | IllegalArgumentException | `leverage must be >= 1` |
| INVALID_RATE | IllegalArgumentException | `maintMarginRate must be in (0, 1)` |
| INVALID_SIDE | IllegalArgumentException | `positionSide must be LONG or SHORT` |

调用方的**领域异常**（如 `PositionService` 的 `RejectFillException`、
`TradingTransactionHelper` 的 `InvalidOrderException`）由调用方在自己的 guard 层抛，
内核不感知；内核异常类型只在 §4 表内。新增错误码必须同步两侧 runner 的 ERROR_MAP。

## 5. 差分对拍（fixtures）

- 位置：`tests/fixtures/perp/*.json`，每文件一个用例，命名 `函数_场景.json`。
- 格式：

```json
{
  "name": "与文件名一致",
  "description": "一句话说明算什么、期望值怎么来",
  "function": "内核函数名（Java camelCase，§3 各节标题）",
  "input": { "参数名（camelCase）": "十进制字符串", "leverage": 10, "open": true },
  "expected": { "value": "…" }
}
```

  - 单值函数：`expected.value`；布尔谓词：`expected.value` 为 true/false；
    `applyPositionDelta`：`expected` 直接放 `newQty / newAvgEntryPrice / realizedPnlDelta /
    marginDelta` 四字段（可为 null）；错误用例：`expected.error` 为 §4 代码。
  - input 中缺失或 null 的金额参数以 null 传入内核（触发对应校验码）。
- **比较协议**：数值一律**数值相等**（Java `isEqualByComparingTo`，Python `Decimal ==`），
  忽略 scale 表示差异——scale 契约由 §2/§3 的类别声明约束，舍入错误在数值上必然可见
  （专用 HALF_UP 边界 fixture 钉住舍入方向）；布尔/ null 直接等值比较。
- runner 职责：只做「解析 → 分派 → 比较 / 错误映射」，**不含业务逻辑**；未知 function 名
  必须让测试显式失败（防 typo fixture 静默跳过）。
- CI 双门控：
  - JUnit `src/test/java/com/kwikquant/shared/types/PerpMathFixturesTest.java`：
    全量 fixtures 过 `PerpMath`。
  - pytest `tests/python/test_perp_math_fixtures.py`：同一批 fixtures 过 `perp_math`。
  - 与 `tests/fixtures/matching/`（docs/matching-spec.md §8）同一套差分对拍机制。

## 6. 修改流程

1. 先改本文档（运算顺序 / 舍入点 / 校验 / 错误码）。
2. 再增改 `tests/fixtures/perp/` 用例（期望值**手算自本文档**，不得从任一实现抄）。
3. 同步 Java `PerpMath` 与 Python `perp_math.py`。
4. 双侧跑 fixtures：`./mvnw test -Dtest=PerpMathFixturesTest -Pno-spotless` 与
   `.venv-worker/bin/python -m pytest tests/python/test_perp_math_fixtures.py`。
5. 消费点行为变化（若有）必须能逐条归因到本文档的声明变更，并在 commit message 说明。
