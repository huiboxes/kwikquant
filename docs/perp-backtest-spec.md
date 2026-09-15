# PERP 回测规范（Perp Backtest Spec）

> **单一真相源**。PERP 回测引擎的账本、强平近似与资金费回放语义以本文档为准；单标的实现是 Python
> `kwikquant_worker/backtest/perp_ledger.py` + `event_loop.py`（PERP 分支），组合（多标的）实现是
> `perp_ledger.py::PerpPortfolioLedger` + `portfolio.py`（PERP 分支，§10）。钱数学**全部**委托
> `kwikquant_worker/perp_math.py`（规范 `docs/perp-math-spec.md`，与 Java `PerpMath` 差分对拍）；
> 订单接受性走 `docs/matching-spec.md` §9（双侧对拍）；撮合走 `docs/matching-spec.md` §1–§8。
> 本文档只管三者的**编排与回测特有近似**。改语义必须：先改本文档 → 再改实现与 pytest 用例。

## 1. 适用范围与定位

| 场景 | 支持 | 说明 |
|---|---|---|
| 单标的 PERP 回测（`on_bar(bar, ctx)`） | ✅ | 本文档范围 |
| 组合（多标的）PERP 回测（`on_bars(ctx)`） | ✅（§10） | 组合账户账本（共享现金 + per-symbol 净持仓）；CROSS 账户级保证金聚合、Model B 单腿脉冲强平 |
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
6. **事件派发**：强平成交后派发策略 `on_liquidation(ev, ctx)` 回调（payload 见
   `docs/strategy-api.md` §8；策略未定义则不派发）。**不双派 `on_fill`**——对齐 runner 侧
   `/topic/liquidations` 与 `/topic/fills` 的通道互斥（Java `LiquidationService` 落 Fill 行
   但只 publish `LiquidationEvent`，不广播 `FillEvent`），两运行时同一去重语义。

### 4.2 已知失真清单（报告 `liquidation_model="BAR_EXTREME_APPROX"` 强制声明）

- bar 内 high/low 先后顺序未知：可能把"先涨后跌未触强平价"误判为强平（保守偏差）；
- 粗粒度 bar（≥1m）内插针路径遗漏；参考价成交假设强平单在参考价有流动性；
- CROSS 触发价用极值成交，比真实清算价更保守；
- 无 ADL（自动减仓）模拟、无保险基金赔付、强平费率简化为 taker fee、无强平罚金；
- `mmr` 用简化常数默认 0.005（`perp_math.DEFAULT_MAINT_MARGIN_RATE`），非交易所阶梯维持保证金率。

## 5. 资金费事件回放（期次网格，精确时间戳，左开右闭归属）

数据源：`funding_rates` 已结算序列（`settled_rate` 非空行，ASC），worker 经
`GET /api/v1/backtests/{taskId}/funding-rates`（X-Worker-Token）拉取，区间 ⊆ 任务快照。

资金费期次是时间轴上的**独立事件节点**（FUNDING，timestamp = 精确 `funding_time`，
可落在 bar 中段），与 BAR 节点归并派发（§6）。bar 连续覆盖时间轴，不存在"bar 之间"的
空隙——事件化的语义是期次以**自身精确时间戳**参与排序与结算，而非压缩进 bar 边界批处理。

1. **期次归属**：FUNDING(T) 排在所有 `open_time < T` 的 BAR 之后、所有 `open_time ≥ T`
   的 BAR 之前。连续时间轴下的等价表述：bar（open_time = ts，周期 = timeframe 折算）结算
   所有 `funding_time ∈ (ts, ts + interval]` 的期次——左开右闭，相邻 bar 无缝不重叠
   （`funding_time == ts` 归属上一根 bar）。时间轴断档（交易所停摆/数据缺口）时，漏期由
   下一根存在的 bar **catch-up 补结**（与 paper 资金费调度 catch-up 语义一致，绝不静默漏收）；
   首根 bar 的 `funding_time == ts` 边界期归首根（此时通常 flat，不收费，无害）。
2. **结算时点**：归属 bar 的强平与撮合**之后**（保守口径：先定仓位再收费），on_bar 之后、
   equity 记录之前——持仓基线（归属 bar 末净持仓）与 equity 曲线口径（本 bar 点反映本 bar
   归属期次的 `funding_cum`/`margin_used`）与 bar 驱动时代一致。每期结算后派发策略
   `on_funding(ev, ctx)` 回调（payload 见 `docs/strategy-api.md` §8；策略未定义则不派发）。
3. **金额（mark 真值化）**：`funding_amount(side, settled_rate, mark, |q|)`（内核；qty 用
   归属 bar 末净持仓方向派生 side）。`mark` **优先取期次行自带 `mark_price`**（交易所结算
   真值，T 时刻已定，无未来函数）；行内缺失**或 ≤0**（历史/回填行可为 null；脏行守卫与
   Java `PaperFundingSettlementScheduler` 对同源数据的 `signum()<=0` 守卫同纪律——0 会
   静默零收费、负值会翻转资金费符号，都是错收）视同缺失，fallback **归属 bar close**
   （最近已收盘价的文档化近似）。旧口径（一律 bar.close、真值行不用）已废弃：
   close 代理是回测无期次级 mark 序列时代的唯一选择，真值可得后仍用代理属故意失真。
   同一 bar 内多个期次各自取自身 mark（不再强制共用 close——真值间的差异是市场事实，
   不是口径噪声）。
4. **入账**：ISOLATED → `cash += f` 且 `margin += f`（收增厚/付侵蚀仓位保证金，margin 可负，
   与 paper D4 侵蚀语义一致；`available` 不变）；CROSS → 仅 `cash += f`（账户担保）。
   `funding_cum += f`。
5. **flat 期次**：跳过（无持仓不收费），**不派发 `on_funding`**——与 runner 侧同构
   （Java 无结算落账行即无 `FundingSettlementEvent` 推送），策略在两侧都只在真发生
   资金费转账时收到事件。
6. **interval 不硬编码**：期次间隔以序列行 `interval_seconds` 为准（1h/4h/8h 通吃）；bar 归属
   边界用 timeframe 折算（两者独立：8h 资金费 × 1h bar = 每 8 根 bar 结算一期）。
7. **缺期防御**：提交/执行预检 fail-closed（§7）；运行期序列缺期（数据被删等）→ worker 以
   `FUNDING_DATA_MISSING:` 前缀 stderr + exit 3 → Java markFailed（FUNDING_DATA 分类，
   ErrorCode 7308 语义；专属 userMessage 给"缩短区间/开资金费代理"出路），绝不静默漏收。
   运行期检测以行内 `interval_seconds` 为前提，而历史/回填行该列恒 null——Java 端点下发前
   **逐行富化局部 interval**（声明缺失行取 min(前后相邻差分)，低于网格候选下限
   （2880s=1h 网格负抖动容差下界，`FundingSeries.isGridCandidate`）不富化不猜；
   `FundingSeries.enrichLocalIntervals`），纯回填序列的运行期防线由此不失效。

## 6. 事件时间轴与闸门顺序（单标的 PERP）

时间轴 = **BAR 节点**（bar 收盘处理包，timestamp = open_time）与 **FUNDING 节点**
（资金费期次，timestamp = 精确 `funding_time`，§5.1）按时间戳归并排序逐个派发。
策略事件回调（`on_fill`/`on_liquidation`/`on_funding`，契约见 `docs/strategy-api.md` §8）
在对应节点内派发；回调内的 `place_order` 与 on_bar 内同一队列、同 NEXT_BAR 语义。
**NEXT_BAR 是结构不变量**：意图队列在 BAR 节点开头（强平判定之前）一次性 drain，节点内
一切派发点（含 on_liquidation）与 on_bar 下的单最早在下一根 bar 撮合——不依赖调用顺序
巧合，杜绝"强平回调内下单被本 bar 撮合"的前视偏差。

```
BAR 节点（bar open_time = ts，OHLC）处理包：
  1. 强平判定与成交（§4，用上一 bar 末仓位 × 本 bar 极值；成交后派发 on_liquidation，
     不双派 on_fill，§4 规则 6）
  2. 对上一节点排队的每个意图：
     acceptance（§2 / matching-spec §9）→ 撮合（matching-spec §3–§6）
     → 账本闸门：CLOSE_* 只查 §3.3 合法性（超仓/方向矛盾拒）；含 OPEN 段的意图
       （新开/加仓/穿零反转/OPEN_* 减仓）按**预测段序执行后** `available' = cash' − margin' ≥ 0`
       净闸门（fee 先扣、CLOSE 段 pnl/释放先入账、OPEN 段 initial_margin 计入 margin'；
       CROSS 存量 margin 恒 0，公式与 ISOLATED 统一）。纯减仓（OPEN_* 异号 |d|≤|q|，
       无 OPEN 段）无现金闸门——亏损实现是账务事实（cash 可为负），交易所也不拒 reduceOnly 平仓单
     → 应用（内核 apply_position_delta，反转拆两段）→ trade 记录
     → 每笔成交应用后派发 on_fill（先于 on_bar——策略进入 on_bar 时本 bar 成交已知）
  3. on_bar(bar, ctx)（策略产生新意图，NEXT_BAR 撮合）

FUNDING 节点（T ∈ (ts, ts+interval]，排在 BAR(ts) 之后、BAR(ts+interval) 之前）：
  4. 资金费结算（§5：mark 真值/fallback、入账、flat 跳过；每期派发 on_funding）

BAR 节点收尾：
  5. equity 记录（close mark-to-market，含本 bar 归属期次的 funding_cum/margin_used）
```

归并的对齐细节：步骤 4 逻辑上属于 BAR(ts) 与 BAR(ts+interval) 之间的独立节点，但 equity
记录（步骤 5）必须消费本期结算结果（口径与 bar 驱动时代一致，§5.2）——实现上 FUNDING
节点与其归属 BAR 的收尾绑定派发，**排序语义以 §5.1 归属规则为准**。

拒单不是错误：acceptance/闸门拒单记 warning（上限 10 条）后继续回测（与 SPOT 既有语义一致）。
回调异常与 on_bar 同级：回测 fail-fast 整个任务（`RuntimeError`），runner 记 stderr 继续
（`docs/strategy-api.md` §6 异常语义按回调类型逐一适用）。

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
  期数 + `fundingSchema` 行形态字段名列表；hash 输入为 `{"schema": [...], "rows": [...]}`，
  rows 覆盖行全部结算输入列 `funding_time/settled_rate/interval_seconds/mark_price/source`
  ——v5 起 `mark_price` 是结算输入（§5.3），不入 hash 则"同快照 ⇒ 同结果"承诺被数据订正
  静默打破；schema 入 hash ⇒ `fundingVersion` 单值即完整承诺"同数据+同行形态"，字段序/
  个数变更必然换 hash，跨 run 比对无需以引擎版本推断形态），pairSpecs 快照**原文入库**
  （`reproducibility.pairSpecs`，可直接审计/复跑对账），与 klines payload hash
  （`data.version`）同级，保证回测可复现。

## 8. 报告输出扩展（section8 JSON）

- `trades[]`（PERP 额外字段，SPOT 不变）：`position_effect`；强平行另有 `"liquidation": true`。
- `equity_curve[]`（PERP 额外列，SPOT 不变）：`margin_used`、`funding_cum`。
- 顶层（仅 PERP）：`"market_type": "PERP"`、`"liquidation_model": "BAR_EXTREME_APPROX"`。
- `warnings[]`：acceptance 拒单（reasonCode+message，英文——与对拍层消息逐字一致，不翻译）、
  闸门拒单、强平事件（`强平于 {ts}：{side} {qty} @ {price}（{marginMode}）`）、跨所代理标注
  （`资金费跨所代理：{n} 期费率取自 PROXY_BINANCE…`）、末根 bar 未撮合订单标注、**尾部漏期
  标注**（K 线提前结束（actualEnd < 任务 end）时，落在最后一根 bar 归属窗之后、任务 end
  之前的已结算期次永不参与回放——`K 线提前结束：{n} 期已结算资金费未参与回放（首漏期 {ts}…）`，
  §5"绝不静默漏收"承诺的收尾防线；前瞻缓冲区内（> 任务 end）的期次属窗外数据，不标注。
  task_end 不可解析（防御面：非 tz-aware ISO 等，生产路径 Jackson Instant→ISO 恒合法）时
  漏期检测降级为警示（`task_end … 不可解析…，尾部漏期检测跳过`），结果照常产出——纯诊断
  功能不得把已完整跑完的回测作废）。
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

## 10. 组合（多标的）PERP 回测（`on_bars(ctx)`）

> 组合 PERP 复用单标的的**每标的**账本语义（§2 意图 / §3 净持仓应用 / §4 强平谓词 / §5 资金费），
> 只在**账户级**扩展：共享现金池、跨标的保证金聚合（CROSS）、联合时间轴。实现是
> `kwikquant_worker/portfolio.py`（`PortfolioEventLoop` PERP 分支 + `PortfolioContext` PERP 形态）
> 与 `perp_ledger.py::PerpPortfolioLedger`（组合账户账本）。钱数学仍**全部**委托 `perp_math` 内核。

### 10.1 组合账户账本（共享 cash + per-symbol 净持仓）

- 一个账户账本持**共享 `cash`**（quote，含被锁定保证金，§3.1 同口径）与 `positions: dict[symbol → 净持仓]`。
  每个净持仓的状态变量、四向应用规则（§3.2 / §3.3）、加权均价与穿零反转与单标的**逐字一致**——
  组合只是把"一个净持仓"换成"一组按 symbol 分桶的净持仓"。
- **不是** N 个独立子账本：现金是全组合共享的一格，`equity()` / `available_cash()` 是账户级聚合。
  独立子账本会切碎初始资金，且无法表达 CROSS 跨标的保证金耦合（§10.2）。
- `leverage` / `margin_mode` **每 symbol 独立**首仓锁定（与单标的每仓锁定同构）：同一组合内不同标的
  可用不同杠杆/保证金模式；同标的后续订单必须一致（`LEVERAGE_MISMATCH` / `MARGIN_MODE_MISMATCH`）。
- 净持仓语义不变（§1）：每标的单向净持仓，不引入对冲双向。市场中性靠"long A 的净多 + short B 的净空"表达。

### 10.2 保证金聚合（ISOLATED 仓位级 / CROSS 账户级）

派生量（mark 向量 p = 各 symbol 的评估价）：

- `Σ_iso_margin` = Σ 所有 ISOLATED 持仓的 `margin`（CROSS 持仓 margin 恒 0，§3.1）；
- `available = cash − Σ_iso_margin`（现金闸门用，§10.3）；
- `equity(p) = cash + Σ_all unrealized_i(p_i)`（账户权益，mark-to-market；所有持仓，不分模式）。

**CROSS 账户级保证金（隔离 ISOLATED 锁定额）**：CROSS 持仓由账户**自由现金**共同担保，
ISOLATED 锁定保证金已划入各自仓位、不参与 CROSS 担保：

- `free_backing = cash − Σ_iso_margin`；
- `cross_margin_balance(p) = free_backing + Σ_cross unrealized_i(p_i)`；
- `cross_maint(p) = Σ_cross maintenance_margin_required(p_i, |q_i|, mmr)`（内核 EXACT，聚合后**一次**比较，
  避免逐仓 dust 累积，perp-math-spec §3.5）；
- 账户 CROSS 穿仓 ⟺ `margin_breached(cross_margin_balance(p), cross_maint(p))`（内核谓词，§4 规则 2）。
- 单标的 CROSS（无 ISOLATED 持仓）时 `free_backing = cash`、聚合退化为单仓 `cash + unrealized`——
  §4 单标的 CROSS 口径是本式 N=1 特例。

**ISOLATED 持仓**：穿仓判定仍是**仓位级**（`margin_balance = margin + unrealized`，§4 规则 2），
与账户其他持仓解耦——每仓只看自己的锁定保证金。

### 10.3 现金闸门（账户级 available）

单标的现金闸门（§6 步骤 2）推广到账户级：含 OPEN 段的意图，预测段序执行后
`available' = cash' − Σ_iso_margin' ≥ 0`（fee 先扣、CLOSE 段 pnl/释放先入账、OPEN 段
initial_margin 计入下单标的 margin'）。**其余标的的 margin 在本单执行中不变**——闸门是
"本单标的 projected margin + 其余标的存量 margin"的账户级和。纯减仓 / CLOSE 合法性（§3.3）
判定逐标的不变（reduceOnly 无现金闸门）。

### 10.4 强平（Model B：单腿脉冲情景 + 全平清算）

组合强平必须回答单标的没有的问题：**bar 内各标的极值不在同一时刻发生**。把"各标的同时取逆向极值"
代入账户聚合（记为 Model C）会假设 long A 见 low **且** short B 见 high 于同一 bar 发生——对相关
对冲腿物理不可能，会系统性误强平健康的市场中性组合。回测采纳 **Model B**：任一**单腿**脉冲到其逆向
极值、其余标的留当前 mark，穿仓即触发并**全平所有 CROSS 仓**（不做单仓渐进的理由见规则 3）。

**mark 定义**：本时间轴步某标的有新收盘 bar → 其 `open/high/low/close` 可用；无新 bar（联合时间轴
稀疏步）→ 持其**最近已收盘 close**（stale mark，不参与脉冲）。

**ISOLATED 持仓**（与账户解耦，顺序无关）：逐仓按 §4 判定 / 成交（逆向极值触发、open 跳空按 open、
否则参考价），各自全平。在 CROSS 评估**之前**按 symbol 升序处理——ISOLATED 强平改 `cash` 与
`Σ_iso_margin`、进而影响 CROSS `free_backing`，先处理保求值确定。

**CROSS 持仓**（账户级，Model B）：

1. **情景**（仅本步有新 bar 的 CROSS 标的能脉冲）：
   - `gap`：本步有新 bar 的 CROSS 标的取 `open`，其余取当前 mark；
   - `adverse[s]`：CROSS 标的 s 取其逆向极值（LONG→low / SHORT→high），其余取当前 mark（逐个 s）。
2. **触发**：按固定序——`gap` 优先，其后 `adverse[s]` 按 s 升序——第一个令
   `margin_breached(cross_margin_balance, cross_maint)` 为真的情景为**驱动情景**；无则不强平。
3. **全平清算**（在驱动情景的价向量上）：驱动情景一旦穿仓，**全平所有 CROSS 持仓**——逐仓
   `cash += closed_pnl(side, avg, exec_price_s, |q|) − 强平fee`（`exec_price_s` = 该标的在驱动情景下的价：
   脉冲标的即其极值，其余即当前 mark），margin 释放（CROSS 恒 0），置 flat、记 `LiquidationRecord`
   （symbol 升序）。**不做单仓渐进**：bar 极值近似下单仓清算优先级无干净定义——崩盘腿价低使其
   `maint` 反而最小、"最大 maint 优先"会先平健康腿、"最大亏损优先"会先实现最大亏损，无一无副作用；
   账户已穿仓即全平是保守（§4"宁可高估风险"方向）且确定可复现的简化，与单标的全平语义一致。
   Model B 的对冲保护体现在**触发侧**（单腿脉冲情景不假设两腿同时逆向，健康对冲不会进入穿仓判定），
   而非清算侧。

**N=1 CROSS 退化**：唯一 CROSS 标的时 `free_backing = cash`，`gap` = open、`adverse[s]` = 逆向极值，
全平 = 平该唯一仓——**逐字复现 §4 单标的 CROSS**（open 跳空→open，否则极值）。组合引擎对单标的
PERP 的**成交与权益数值序列**与单标的引擎逐字节一致（差分测试 §10.9 锁定；section8 外壳因组合封装
`symbols`/分标的 `positions`/trade 带 `symbol` 而形态不同，逐字节指 trades/equity_curve 的**数值字段序列化**）。

**成交后**派发 `on_liquidation`（§4 规则 6，不双派 on_fill）。账户级失真清单继承 §4.2，追加：
"CROSS 组合 intrabar 联合路径不可知——Model B 以单腿脉冲近似，不模拟多腿同 bar 联合极值"。

### 10.5 资金费回放（per-symbol 多序列）

每标的一条已结算序列 + 一个 `FundingReplay` 游标（§5 逐字一致：左开右闭归属、catch-up、mark 真值化、
flat 跳过、缺期 fail-closed）。本步对**有新 bar 的标的**逐个结算其归属窗 `(bar_open, bar_open + timeframe]`
的期次并派发 `on_funding`。任一标的缺期 → 整个组合任务 fail-closed（exit 3 → 7308）；尾部漏期按标的
独立诊断进 warnings。资金费入账仍逐标的按 §5.4（ISOLATED cash+margin 同增减 / CROSS 仅 cash）。

### 10.6 事件时间轴（联合时间轴）

时间轴 = 所有标的 bar timestamp 的排序并集（SPOT 组合 `PortfolioEventLoop._timeline` 同源）。
每步 `ts` 的处理包（仅对本步有新收盘 bar 的标的做撮合 / on_funding；强平是账户级一次）：

```
组合 BAR 步（ts）：
  1. 强平（§10.4：先 ISOLATED 逐仓、后 CROSS 账户级；用上一步末各仓 × 本步各标 mark）
  2. 对上一步排队意图，逐标的：撮合 → 账户级闸门（§10.3）+ §3.3 CLOSE 合法性 → 应用（§3.3）→ trade
     → on_fill（逐笔，先于 on_bars）    ——缺 bar 标的的挂单结转（SPOT 组合同语义）
  3. on_bars(ctx)（策略产生新意图，NEXT_BAR）
  4. 资金费（§10.5：逐有新 bar 标的结算归属期次，派 on_funding）
  5. equity 记录（账户 equity(各标 current mark)，含 margin_used = Σ_iso_margin、funding_cum = 账户累计）
```

NEXT_BAR 结构不变量对每个标的成立（§6）；缺 bar 标的挂单结转，等其下一根可用 bar。

**组合 PERP 撮合前逐 symbol 过 acceptance**（与单标的 PERP 同源 `acceptance.check` + per-symbol `pairSpecs`）：
leverage 值域（1–100 + per-symbol `maxLeverage`）、`minQty`/`stepSize`/`tickSize`、`position_effect` 等接受性
规则（matching-spec §9 规则 12/16/17 等）越界一律**拒单进 warnings**，不再落到账户账本闸门内 `initial_margin`
抛 `ValueError`（`leverage<1` 曾使整任务不透明 FAILED）或静默受理越界 leverage（曾产出不真实保证金/乐观强平的
误导报告）。`pairSpecs` 缺该 symbol → `UNKNOWN_SYMBOL` fail-closed 全拒。加上共享 `normalize_order`（effect 必填
四向、amount>0、拒 float）+ 账户账本闸门（§10.3 现金 / §3.3 CLOSE 超仓 / 开仓 leverage&marginMode 必填、同标的
一致性），组合 PERP 的受理性与单标的 PERP **同一真相源**，"组合是单标的账户级泛化"承诺在无效输入维度亦成立。
**非对称声明**：SPOT 组合引擎**不跑** acceptance（存量行为，SPOT 无杠杆/无强平，越界值域无害；补 acceptance
会改动存量 SPOT 组合序列化字节、构成可复现回归，收益仅风格统一）——故 acceptance 仅在组合 **PERP** 路径运行。

### 10.7 报告输出（section8）

复用 §8 PERP 字段，叠加组合形态（SPOT 组合 `_to_portfolio_section8` 已有 `symbols` / 分标的 `positions` /
trade 带 `symbol`）：

- 顶层：`market_type="PERP"`、`liquidation_model="BAR_EXTREME_APPROX"`、`symbols`；
- `trades[]`：`symbol` + `position_effect`（强平行另有 `liquidation: true`）；
- `positions{symbol → {qty(signed), avg_price, leverage, margin_mode, margin}}`；
- `equity_curve[]`：`margin_used`（= Σ_iso_margin）、`funding_cum`（账户累计）；
- Java 落库（§8.1）按报告级 `market_type` 分派，PERP 行必带 `position_effect`（混排拒 9002）；
  `PerformanceCalculator`（§8.2）PERP signed-FIFO 配对**按 symbol 分组**（每标的独立净持仓序列），
  组合报告逐笔 `equity` 列置 null（§8.2 组合先例）。导出/导入仍 SPOT-only（§8.1）。

### 10.8 Java 侧放开（提交 / 执行双卡点）

- 删组合 PERP 双端硬拒（提交 `BacktestTaskService`、执行 `BacktestExecutionGateway`）；
- funding 预检（§7）**逐 symbol**对照（现单 symbol 口径对组合会把逗号拼接串当 symbol）；
  `allowFundingProxy` 透传组合提交；
- worker 期次拉取端点鉴权改 portfolio-aware（请求 symbol 须 ∈ 任务 symbols）；
- pairSpecs 快照对组合每 symbol fail-closed（已有 per-symbol 迭代）；
- reproducibility：funding hash 逐 symbol 富化并入（否则"同快照 ⇒ 同结果"对组合被打破）。

### 10.9 验证

- pytest：`test_perp_portfolio.py`（账户账本 / CROSS 聚合 / Model B 强平矩阵 / per-symbol 资金费 /
  混合模式）；**N=1 退化差分**——同一单标的 PERP 策略经组合引擎（1 标的）与单标的引擎的 trades/equity_curve
  **数值字段序列化逐字节一致**（含零 fee 场景守护 `norm` 负零/精确零规范化；section8 外壳形态不比对）。
- 三 ctx 差分（`test_context_contract.py`）：组合 PERP 纳入 PERP 意图差分集（原排除 portfolio），
  收紧覆盖；组合 ctx PERP 下单接受性与单标的 / runner 同 `normalize_order` 单源。
- Java：预检 per-symbol / 放开点单测 + PERP 组合 `PerformanceCalculator` 分组配对矩阵 + `clean verify`。
