# PERP 回测规范（Perp Backtest Spec）

> **单一真相源**。PERP 回测引擎的账本、强平近似与资金费回放语义以本文档为准；实现是 Python
> `kwikquant_worker/backtest/perp_ledger.py` + `event_loop.py`（PERP 分支）。钱数学**全部**委托
> `kwikquant_worker/perp_math.py`（规范 `docs/perp-math-spec.md`，与 Java `PerpMath` 差分对拍）；
> 订单接受性走 `docs/matching-spec.md` §9（双侧对拍）；撮合走 `docs/matching-spec.md` §1–§8。
> 本文档只管三者的**编排与回测特有近似**。改语义必须：先改本文档 → 再改实现与 pytest 用例。

## 1. 适用范围与定位

| 场景 | 支持 | 说明 |
|---|---|---|
| 单标的 PERP 回测（`on_bar(bar, ctx)`） | ✅ | 本文档范围 |
| 组合（多标的）PERP 回测（`on_bars(ctx)`） | ❌ 明确拒 | Java 提交入口与 worker 双端 fail-closed |
| SPOT 回测 | ✅ 账本公式与输出结构不变 | 存量 pytest 不注入 pairSpecs 时回归 diff=0。**新增行为**（matching-spec §7/§9，生产 SPOT 任务随 pairSpecs 下发启用）：acceptance 闸门（amount 不对齐 stepSize 等从"成交"变"拒单进 warnings"）与 SELL dust clamp（float 残差满仓平仓从假拒变 clamp 成交） |

**净持仓模式声明**：回测账本是**单向净持仓**（`signed_qty`，正=LONG 负=SHORT），≠ 模拟盘的
positionSide 分行（LONG/SHORT 各一行）与 LIVE hedge 双向持仓。这是有意为之的语义差异
（回测研究场景不需要对冲双向），但**钱数学与交易侧同一内核**（`perp_math`，逐位对拍），
差异只在持仓簿记形态。

数据前提（Java 侧提交预检保证，见 §7）：任务区间 K 线可得；`funding_rates` 已结算序列覆盖
任务区间（缺期 fail-closed 拒任务，或显式 `allowFundingProxy` 走跨所代理）；`pairSpecs`
交易对规格快照非空（币单位）。

## 2. 订单意图（PERP 契约）

`ctx.place_order` PERP 形态：

```python
ctx.place_order(position_effect="OPEN_LONG", order_type="MARKET", amount=Decimal("0.01"),
                leverage=10, margin_mode="ISOLATED")          # price 仅 LIMIT 系
```

- `position_effect` **必填**（OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT）；`side` **禁传**——
  由 `position_effect` 派生（与交易侧四象限单源同构，从入口消灭 side/effect 矛盾输入）。
- `leverage`/`margin_mode` 开首仓必填；已有持仓时可选（缺省继承持仓），显式给出则必须与
  持仓一致，矛盾拒单（`LEVERAGE_MISMATCH` / `MARGIN_MODE_MISMATCH`，进 warnings）。
  flat 时无继承源——任何 PERP 意图（含 CLOSE_*）省略 leverage 都会被接受性规则 12 拒
  （`LEVERAGE_RANGE`，与交易侧"PERP 单 leverage 必填"同构），先于账本闸门的
  `CLOSE_OVER_POSITION`。
- SPOT 契约不变（`side` 必填，合约字段禁传——ctx 层 ValueError，镜像接受性规则 19）。
- `amount` 单位 = 币数量（base coin）；金额字段 Decimal/str/int，**拒 float**。

意图排队后 NEXT_BAR 撮合（matching-spec §7）；撮合前过接受性纯函数
（`acceptance.check`，matching-spec §9），拒单原因（reasonCode + message）进 warnings，
**不再静默**。`pairSpecs` 快照缺失该 symbol → `UNKNOWN_SYMBOL` fail-closed 全拒。

## 3. 账本模型（净持仓 + cash/margin 双轨）

### 3.1 状态变量

| 变量 | 语义 |
|---|---|
| `cash` | 总现金（quote，USDT）。**含**被锁定保证金——与 paper `total` 同口径 |
| `signed_qty` | 净持仓（正 LONG / 负 SHORT / 0 flat） |
| `avg_price` | 平均开仓价（flat 时 None） |
| `leverage` / `margin_mode` | 首仓锁定，后续订单必须一致 |
| `margin` | 仓位锁定保证金（ISOLATED 随资金费增减、**可为负**；CROSS 恒 0——账户担保不划转） |
| `realized_pnl` | 累计已实现（净额：毛 PnL − 手续费） |
| `funding_cum` | 累计资金费（持仓视角带符号，正=收） |

派生量：`available = cash − margin`；`unrealized(close) = closed_pnl(side, avg, close, |qty|)`
（内核函数，mark-to-market 同式）；`equity = cash + unrealized(close)`。

守恒：OPEN 只增 `margin` 不动 `cash`（锁定是备注不是划转）；CLOSE `cash += 毛PnL − fee`、
`margin += margin_delta`（内核释放额为负）；任何时刻 `equity` = 初始资金 + Σ净PnL − Σfee + Σfunding。

### 3.2 四向意图 → signed delta 映射

| position_effect | signed delta |
|---|---|
| OPEN_LONG / CLOSE_SHORT | `+qty` |
| OPEN_SHORT / CLOSE_LONG | `−qty` |

### 3.3 应用规则（对当前净持仓 q，意图 delta d）

| 场景 | 处理 | 内核调用 |
|---|---|---|
| q=0，d≠0 | 开仓 | `apply_position_delta(side, is_open=True, 0, None, 0, |d|, price, leverage)` |
| 同号（q·d>0） | 加仓：加权均价、追加保证金 | 同上（current_qty=|q|，current_frozen=margin） |
| 异号且 |d| < |q|，OPEN_* | 减仓 | `apply_position_delta(side, is_open=False, ..., fill_qty=|d|)` |
| 异号且 |d| > |q|，OPEN_* | **穿零反转**：拆两段——先全平当前方向，再按超出量 |d|−|q| 开反方向新仓 | 两次 `apply_position_delta`（CLOSE 段 + OPEN 段） |
| 异号，CLOSE_* 且 |d| < |q| | 减仓 | 同减仓 |
| 异号，CLOSE_* 且 |d| == |q| | **全平**（合法：`close_position()` 与穿蚀仓的唯一主动出路；与内核 `closeQty == currentQty` 全平精确释放、paper 全平同口径） | `apply_position_delta(side, is_open=False, ..., fill_qty=|d|)` |
| CLOSE_* 且 |d| > |q|，或 q=0（flat） | **拒单** `CLOSE_OVER_POSITION`（reduceOnly 语义：平仓不得反手/超仓，与 paper `PERP CLOSE over-position` 拒单一致） | — |

fee 一律从 `cash` 扣（taker/maker 按撮合流动性）；trade 记录用户视角**一条**（反转的两段
内核调用不拆行）。

**穿蚀仓**：ISOLATED 仓位 `margin` 被资金费侵蚀为负时，主动订单一律拒（`MARGIN_DEPLETED`
进 warnings，fail-closed），仓位只能经 §4 强平退出。近似声明：交易所按 equity
（margin+unrealized）判定、unrealized 撑住时仍允许平仓；回测保守拒绝（失去主动止损出路，
偏差方向与 §4 一致）。强平记账相应不走内核 CLOSE 段（内核校验 `currentFrozenMargin` 非负）：
毛 PnL 走内核 `closed_pnl`，margin 全额清零——全平时与内核释放语义等价，负 margin 的亏空
已由资金费入账反映在 cash，§3.1 守恒不破。paper 侧（`PositionService.applyPerpDelta`）对同场景
有同构旁路：穿蚀仓全平（含强平）毛 PnL 走 `PerpMath.closedPnl`、frozen 清零，负释放额经
`BalanceService.applyDepletedMarginRelease` 反向划转（used 归零、free 吸收缺口、total 守恒）；
差异声明：paper 放行手动全平（更接近交易所 equity 判定），回测保守全拒（`MARGIN_DEPLETED`），
部分平仓与加仓两侧一致拒。

## 4. bar 极值强平近似（保守偏差，不声称与交易所等价）

回测无独立 mark price 序列，用 bar 极值代理；bar 内路径不可知，**所有近似朝"宁可高估风险、
低估策略收益"方向**（避免"回测躲过插针、实盘被强平"的乐观偏差）。

### 4.1 语义规则

1. **mark 代理**：LONG 仓用 `low`（bar 内最差价）、SHORT 仓用 `high` 评估触发。
2. **触发判定**：`margin_breached(margin_balance(extreme), maintenance_margin_required(extreme, |q|, mmr))`
   谓词（内核，与 paper D1 语义一致——**不用价格比较**，资金费把 ISOLATED margin 侵蚀穿仓后
   参考价可 ≤0，谓词对 margin ≤ 0 的仓在任何极值下立即触发；margin 为负但未实现盈利撑住
   margin_balance 时（如 margin=−5、unrealized(low)=+1000）谓词不触发，叠加 §3.3 穿蚀仓
   主动单全拒，存在"冻结盈利仓"窗口（只能等极值真正击穿或资金费转正）——保守方向的已知近似）。
   - ISOLATED：`margin_balance(p) = margin + unrealized(p)`（仓位级）；
   - CROSS：`margin_balance(p) = cash + unrealized(p)`（账户级，单标的回测即全部持仓）。
3. **成交价**：开盘已跳空穿越（`margin_breached(margin_balance(open), maint(open))` 亦真）→
   按 **open** 成交（跳空滑点真实性）；否则 ISOLATED 按参考价
   `liquidation_price_isolated(avg, |q|, margin, mmr, side)`（触发时该值必 >0 且落在
   [extreme, open] 区间内），CROSS 无单一参考价 → 按触发极值（保守，文档化近似）。
4. **同 bar 顺序**：强平判定与成交在**本 bar 新订单撮合之前**（用上一 bar 末仓位 × 本 bar 极值）。
5. **资损口径**：强平 = 全平（内核 CLOSE 段），`cash += 毛PnL − 强平fee`（taker 费率）、
   `margin` 全额释放；ISOLATED 穿仓部分（毛PnL < −margin）**从 cash 扣穿**——无保险基金、
   无 ADL，对齐交易所保险基金介入前的真实资损。

### 4.2 已知失真清单（报告 `liquidation_model="BAR_EXTREME_APPROX"` 强制声明）

- bar 内 high/low 先后顺序未知：可能把"先涨后跌未触强平价"误判为强平（保守偏差）；
- 粗粒度 bar（≥1m）内插针路径遗漏；参考价成交假设强平单在参考价有流动性；
- CROSS 触发价用极值成交，比真实清算价更保守；
- 无 ADL（自动减仓）模拟、无保险基金赔付、强平费率简化为 taker fee、无强平罚金；
- `mmr` 用简化常数默认 0.005（`perp_math.DEFAULT_MAINT_MARGIN_RATE`），非交易所阶梯维持保证金率。

## 5. 资金费回放（期次网格，左开右闭）

数据源：`funding_rates` 已结算序列（`settled_rate` 非空行，ASC），worker 经
`GET /api/v1/backtests/{taskId}/funding-rates`（X-Worker-Token）拉取，区间 ⊆ 任务快照。

1. **期次归属**：bar（open_time = ts，周期 = timeframe 折算）结算所有
   `funding_time ∈ (ts, ts + interval]` 的期次——左开右闭，相邻 bar 无缝不重叠
   （`funding_time == ts` 归属上一根 bar）。时间轴断档（交易所停摆/数据缺口）时，漏期由
   下一根存在的 bar **catch-up 补结**（与 paper 资金费调度 catch-up 语义一致，绝不静默漏收）；
   首根 bar 的 `funding_time == ts` 边界期归首根（此时通常 flat，不收费，无害）。
2. **结算时点**：本 bar 强平与撮合**之后**（保守口径：先定仓位再收费），on_bar 之后、
   equity 记录之前。
3. **金额**：`funding_amount(side, settled_rate, mark_proxy=bar.close, |q|)`（内核；qty 用
   该 bar 末净持仓方向派生 side）。回测无期次级 mark 序列，`bar.close` 是文档化近似
   （序列行自带 `mark_price` 时**不用**——同一 bar 内多个期次共用 close 保持口径一致）。
4. **入账**：ISOLATED → `cash += f` 且 `margin += f`（收增厚/付侵蚀仓位保证金，margin 可负，
   与 paper D4 侵蚀语义一致；`available` 不变）；CROSS → 仅 `cash += f`（账户担保）。
   `funding_cum += f`。
5. **flat 期次**：跳过（无持仓不收费）。
6. **interval 不硬编码**：期次间隔以序列行 `interval_seconds` 为准（1h/4h/8h 通吃）；bar 归属
   边界用 timeframe 折算（两者独立：8h 资金费 × 1h bar = 每 8 根 bar 结算一期）。
7. **缺期防御**：提交/执行预检 fail-closed（§7）；运行期序列缺期（数据被删等）→ worker 以
   `FUNDING_DATA_MISSING:` 前缀 stderr + exit 3 → Java markFailed（FUNDING_DATA 分类，
   ErrorCode 7308 语义；专属 userMessage 给"缩短区间/开资金费代理"出路），绝不静默漏收。
   运行期检测以行内 `interval_seconds` 为前提，而历史/回填行该列恒 null——Java 端点下发前
   **逐行富化局部 interval**（声明缺失行取 min(前后相邻差分)，低于网格候选下限
   （2880s=1h 网格负抖动容差下界，`FundingSeries.isGridCandidate`）不富化不猜；
   `FundingSeries.enrichLocalIntervals`），纯回填序列的运行期防线由此不失效。

## 6. 逐 bar 闸门顺序（单标的 PERP）

```
每根 bar（时间戳 ts，OHLC）：
  1. 强平判定与成交（§4，用上一 bar 末仓位 × 本 bar 极值）
  2. 对上一 bar 排队的每个意图：
     acceptance（§2 / matching-spec §9）→ 撮合（matching-spec §3–§6）
     → 账本闸门：CLOSE_* 只查 §3.3 合法性（超仓/方向矛盾拒）；含 OPEN 段的意图
       （新开/加仓/穿零反转/OPEN_* 减仓）按**预测段序执行后** `available' = cash' − margin' ≥ 0`
       净闸门（fee 先扣、CLOSE 段 pnl/释放先入账、OPEN 段 initial_margin 计入 margin'；
       CROSS 存量 margin 恒 0，公式与 ISOLATED 统一）。纯减仓（OPEN_* 异号 |d|≤|q|，
       无 OPEN 段）无现金闸门——亏损实现是账务事实（cash 可为负），交易所也不拒 reduceOnly 平仓单
     → 应用（内核 apply_position_delta，反转拆两段）→ trade 记录
  3. on_bar(bar, ctx)（策略产生新意图，NEXT_BAR 撮合）
  4. 资金费结算（§5，期次 ∈ (ts, ts+interval]）
  5. equity 记录（close mark-to-market）
```

拒单不是错误：acceptance/闸门拒单记 warning（上限 10 条）后继续回测（与 SPOT 既有语义一致）。

## 7. Java 侧预检与下发（提交/执行双卡点）

- **pairSpecs 快照**：**执行时点**从 `TradingPairService` 取任务 symbol 规格（复快照；币单位：
  minQty/stepSize/tickSize/maxQty/maxLeverage——**contractSize 刻意不含**：ArchUnit 禁其出
  market/trading.infrastructure 边界，回测域全币单位无张数消费方），随 `BacktestRunRequest.pairSpecs`
  下发；PERP 任务快照缺失该 symbol → 拒执行。
- **funding 预检**：任务区间 × symbol 对照 `funding_rates` settled 行（读侧先按派生间隔窗口
  去重同期次双行）。网格 interval 优先取行内 `interval_seconds` 声明，声明缺失（历史/回填行
  恒 null）按相邻期次差分派生（`FundingSeries`：重复 ≥2 次且达网格候选下限（2880s，
  1h 网格负抖动容差下界）的最小差分簇），派生不出
  fail-closed 拒。缺期判定是**分段网格**：头段 start→首行 epoch 对齐，中段逐相邻行 gap 按
  两行 interval 较大者判（与 worker `find_funding_gaps` 的 max-interval 判据同口径，历史期次
  切换不误拒/漏检），尾段末行→end；容差 max(60s, interval/4)，近端宽限 1 interval。缺期即拒
  （错误信息列缺失概况：共缺 N 期 + 首缺/末缺时刻 + 两个出路：缩短区间 / 显式
  `allowFundingProxy=true`；目标所即 BINANCE 时无代理源，出路只剩缩短区间）。proxy 放行时
  缺失期用 Binance 同期次值（±容差窗反查最近 settled 行——精确键匹配会被 Binance 自身漂移
  静默打穿）写入（`source=PROXY_BINANCE`），报告 warnings 标注跨所代理存在基差；默认
  false = fail-closed，绝不静默代理（区间内已有 PROXY 行被非 opt-in 任务消费时服务端 warn
  留痕，用户侧披露由报告 warnings 承担）。**已声明的不对称**：PROXY 行写入的是目标所全局
  序列，paper 资金费结算读同一张表不区分 source——代理值会进入未 opt-in 用户的 paper 账本
  （cumulativeFunding/frozenAmount/强平参考价）。当前决策=照常结算（跳过=静默漏收，更错），
  披露走服务端 warn + audit metadata `rateSource`；"回测补洞数据与运行时结算数据分离"
  留作后续架构项。
- **reproducibility**：funding 序列以 sha256 进 `data.fundingVersion`（+ `fundingPeriods`
  期数），pairSpecs 快照**原文入库**（`reproducibility.pairSpecs`，可直接审计/复跑对账），
  与 klines payload hash（`data.version`）同级，保证回测可复现。

## 8. 报告输出扩展（section8 JSON）

- `trades[]`（PERP 额外字段，SPOT 不变）：`position_effect`；强平行另有 `"liquidation": true`。
- `equity_curve[]`（PERP 额外列，SPOT 不变）：`margin_used`、`funding_cum`。
- 顶层（仅 PERP）：`"market_type": "PERP"`、`"liquidation_model": "BAR_EXTREME_APPROX"`。
- `warnings[]`：acceptance 拒单（reasonCode+message，英文——与对拍层消息逐字一致，不翻译）、
  闸门拒单、强平事件（`强平于 {ts}：{side} {qty} @ {price}（{marginMode}）`）、跨所代理标注
  （`资金费跨所代理：{n} 期费率取自 PROXY_BINANCE…`）、末根 bar 未撮合订单标注。
  引擎级标注为中文（用户可见诊断面）；**资金费结算统计不进 warnings**——与快照
  `fundingPeriods`/`fundingVersion` 及报告累计资金费指标同源，纯信息项混入风险警示会稀释
  PROXY_BINANCE 等真正需要警觉的信号。拒单类 warning 独立 10 条预算（不与强平/代理标注
  共享数组长度竞争，防强平多发时挤掉拒单诊断）。
- **序列化纪律**：全部金额 `str(Decimal)`；**负零规范化**——全平时内核 `margin_delta` 可为
  `Decimal("-0")`，输出前 `d == 0 → Decimal(0)`（禁止 `"-0"` 进 JSON）。

### 8.1 Java 侧落库与契约（report 模块消费）

`ReportService.submitBacktestResult` 解析 section8 时消费全部 PERP 字段并落库
（V60：`backtest_reports` +`market_type`（存量默认 `'SPOT'`）/`liquidation_model`；
`trade_records` +`position_effect`/`liquidation`）：

- `market_type` 顶层缺省 = SPOT；`liquidation_model` 仅 PERP 非空（PERP 缺失 = 数据损坏，
  9002 拒），原样落库并透出报告详情（前端/AI 必须声明近似模型，§4.2）。
- `warnings[]` **不设独立列**：worker 已把同一数组嵌入 `params._kwikquant.warnings`
  （单一真相源），随 `params` JSONB 落库；前端"可信度提示"与 AI 解读的既有链路消费，
  Java 侧不重复解析。
- `trades[].position_effect` 非法枚举值（非 `OPEN_LONG|OPEN_SHORT|CLOSE_LONG|CLOSE_SHORT`）→
  9002 拒；**混排拒**——`market_type=PERP` 但存在缺 `position_effect` 的行，或 SPOT 报告存在
  带 `position_effect` 的行，都是数据损坏，9002 fail-closed（配对路径按报告级 `market_type`
  分派，不允许行级混合语义）。
- `equity_curve[].margin_used/funding_cum`（字符串金额）→ 解析为可空 `BigDecimal` 随
  `EquityPoint` 序列化回 `backtest_reports.equity_curve` JSONB，透出报告详情。
- **导出/导入闭环不支持 PERP**：`exportForImport` 对 PERP 报告显式拒（9004，与组合报告同
  先例）——导出契约不承载 `position_effect`/`liquidation`，再导入必按 SPOT 语义错配。
  import 契约保持 SPOT-only。
- AI 解读上下文（`ai::ReportContextBuilder`）注入 `marketType`/`liquidationModel` 声明行
  （warnings 已随 params 全文注入既有链路），PERP 成交按 `position_effect` 聚合
  （不按 buy/sell——PERP 的 side 是派生量，buy≠开仓）。

### 8.2 PERP 指标配对口径（PerformanceCalculator）

SPOT 报告（全部行无 `position_effect`）保持既有 buy/sell FIFO 配对**逐位不变**。PERP 报告
按**净持仓 signed FIFO** 配对——与 §3.3 账本应用规则同构，还原穿零反转的两段语义：

- 逐行按时间序处理，`delta = signed(effect) × amount`（§3.2 映射）：
  - 与当前净持仓**同号或 flat**：加 lot（记 open fee）；
  - 与当前净持仓**异号**（仅 `OPEN_*` 可达——`CLOSE_*` 超仓已被引擎闸门拒，不会出现在
    数据中；防御性出现则同 SPOT naked 语义宽容跳过）：先 FIFO 消耗对侧 lot 生成 CLOSE 配对
    段，穿零余量转为新方向 lot（§3.3 反转两段）。
- 配对段 pnl 与内核 `closed_pnl` 同式（毛口径）：
  long 段 `(close.price − open.price) × qty`，short 段 `(open.price − close.price) × qty`，
  各减两端 fee share（`fee × matchQty / totalQty`，scale 8 HALF_UP）。
- 强平行（`liquidation=true`，effect 为 `CLOSE_*`）正常参与配对；其 pnl 即强平毛损益。
- **资金费不归入配对段**：`winRate`/`profitFactor`/`avgTradeDurationSeconds` 是毛配对口径；
  资金费与未实现盈亏已含在权益曲线中，`totalReturn`（曲线优先）不受影响。
- 逐笔 `realizedPnl`：CLOSE 行 = Σ(配对段 pnl) + 回加配对段中的开仓侧 fee share（开仓 fee
  已在 OPEN 行计入）；OPEN 行 = −fee。逐笔累计 `equity` 列 **PERP 置 null**——trade 口径的
  累计权益不含未实现/资金费，与权益曲线必然背离，置空避免误读（组合报告同先例）。

## 9. 验证

- pytest：`tests/python/test_perp_ledger.py`（账本/反转/强平/资金费单元矩阵）+
  `test_event_loop.py`（PERP 端到端 bar 序列）+ 存量 SPOT 用例**零改动全绿**（回归 diff=0）。
- 内核正确性不在本层重复验证：`perp_math` 由 `tests/fixtures/perp` 双侧对拍锁定，
  acceptance 由 `tests/fixtures/matching/acceptance_*.json` 锁定。
- Java 侧：预检拒单/pairSpecs 下发/解锁点单测 + `clean verify` 全绿。
- 报告消费侧（§8.1/§8.2）：`PerformanceCalculatorTest` PERP 配对矩阵（往返/加仓部分平/
  穿零反转/强平行/SHORT 镜像，期望值手算）+ SPOT 存量用例**零改动全绿**（回归 diff=0）+
  `ReportServiceTest` 解析/混排拒/导出拒。
