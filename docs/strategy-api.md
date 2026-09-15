# 策略 API 参考（StrategyContext 契约）

> 策略作者的唯一契约参考。代码级单一真相源是 `kwikquant_worker/context.py`
> （`StrategyContext` Protocol + `OrderAck` + 共享入参校验 `normalize_order`），
> 三个运行时（单标的回测 / 组合回测 / 模拟盘实盘 runner）由差分测试锁死同构。
> PERP 回测的账本 / 强平近似 / 资金费语义见 [perp-backtest-spec.md](perp-backtest-spec.md)；
> 撮合与接受性规则见 [matching-spec.md](matching-spec.md)。

## 1. 策略形态

策略是一份纯 Python 源码（平台核心不绑定 numpy/pandas，需要可自行 import），顶层定义入口函数：

- **单标的**（回测 + runner）：`def on_bar(bar, ctx)` — 每根已收盘 K 线调用一次；
- **组合（多标的）回测**：`def on_bars(ctx)` — 每个公共时间轴步调用一次（SPOT 与 PERP，仅回测；PERP 组合语义见 [perp-backtest-spec.md](perp-backtest-spec.md) §10）。

> **组合回测提交入口**：前端策略页底部控制栏切「组合」模式选 2-20 个标的；CLI `kwikquant backtests submit <strategyId> --symbols A,B ...`；MCP `run_backtest(symbols=[...])`；或直调 REST `POST /api/v1/backtests`（body 传 `symbols` 数组）。组合回测要求策略代码定义 `on_bars(ctx)` 入口。

可选事件回调（三运行时同构，见 §8）：顶层定义 `def on_fill(fill, ctx)` /
`def on_funding(ev, ctx)` / `def on_liquidation(ev, ctx)`，成交 / 资金费结算 / 强平
发生时被调用；**不定义则不派发**（存量策略零影响）。

runner 启动时**默认预填最近 200 根已关闭 K 线**到 `history`（消除重启失忆；只灌历史不触发
on_bar，回测天然全量预载）。可选模块级常量 `WARMUP_BARS = N`（上限 999）：声明后按 N 回填，
与默认预填按 openTime 去重叠加（同一区间不重复灌，指标不受污染）。

```python
from decimal import Decimal

FAST = int(PARAMS.get("fast", 5))                    # 任务 parameters 注入(见 §5)
AMOUNT = Decimal(str(PARAMS.get("amount", "0.01")))  # 金额一律 Decimal/str,禁 float

def on_bar(bar, ctx):
    closes = ctx.history("close", FAST + 1)          # 行情是 float(非金额)
    if len(closes) < FAST + 1:
        return
    pos = ctx.position()
    if closes[-1] > sum(closes[:-1]) / FAST and pos.qty == 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
    elif closes[-1] < sum(closes[:-1]) / FAST and pos.qty > 0:
        ctx.close_position()                          # 账本原值全平,零精度残差
```

## 2. ctx 契约（三运行时统一签名）

```python
ctx.params                                   # Mapping[str, Any] 只读,任务/策略绑定 parameters
ctx.symbol                                   # str 绑定交易对(组合 ctx 返空串)
ctx.place_order(*, symbol=None, side=None, order_type, amount,
                price=None, position_effect=None,
                leverage=None, margin_mode=None) -> OrderAck
ctx.close_position(symbol=None) -> OrderAck   # 市价全平(账本原值下单)
ctx.position(symbol=None) -> Position         # 账本持仓副本(PERP qty 为 signed 净持仓)
ctx.equity() -> Decimal                       # 账户权益(quote 计)
ctx.available_cash() -> Decimal               # 可用现金/保证金(quote 计)
ctx.history(field, n, symbol=None) -> list[float]  # 最近 n 根(含当前)OHLCV
ctx.cancel(order_id) -> None                  # 回测 no-op(限价单单 bar 过期);runner 真撤
ctx.report_progress(processed, total) -> None # 仅回测有进度;runner no-op
ctx.log(msg) -> None                          # stderr
ctx.predicted_funding_rate(symbol=None) -> Decimal | None  # 仅 runner;回测/组合抛 NotImplementedError(§9)
```

`symbol` 参数：单标的回测与 runner 可省（用绑定值，显式传入必须一致否则 ValueError）；
**组合 ctx 必传**（无单一绑定交易对；`place_order` 对白名单外 symbol 拒单，
`history`/`position` 对白名单外不抛错、按空数据处理）。

`order_type` ∈ `MARKET / LIMIT / STOP_MARKET / STOP_LIMIT / TAKE_PROFIT_MARKET /
TAKE_PROFIT_LIMIT / TRAILING_STOP`。**条件单陷阱**：`place_order` 契约不带 stop_price
入参——回测中条件单类要么被接受性层拒（`STOP_PRICE_REQUIRED` 进 warnings），要么撮合
永不触发（fill 返 None 进 warnings），**永不成交**；runner 侧同样无 stopPrice 通道。
止损请用市价单在 `on_bar` 里自管（触发条件由策略代码判定）。

### OrderAck（place_order / close_position 回执）

```python
@dataclass(frozen=True)
class OrderAck:
    accepted: bool                 # 请求被受理
    reason: str | None             # 未受理原因(accepted=True 时 None)
    filled_qty: Decimal | None     # 提交时点成交数量(见下)
    filled_price: Decimal | None   # 提交时点成交均价
```

- **回测**：`accepted=True` = 通过契约校验并已排队。撮合是 NEXT_BAR（下一根 bar），
  接受性 / 账本闸门的拒单**异步**发生并进报告 warnings，不反映在回执；
  `filled_qty`/`filled_price` 恒 `None`。
- **runner**：`accepted=True` = 平台已受理（HTTP 成功，风控 / 冻结已过）；网络失败或
  业务拒单 → `accepted=False + reason`（不抛异常，不中断 runner）。
  `filled_qty`/`filled_price` 是**提交时点**值：PAPER 撮合由行情推送异步驱动
  （提交时通常 `Decimal("0")`）、LIVE 交易所异步回报（`None`）。
- **不要以 `filled_qty` 判成交**（旧的 `f.qty > 0` 惯用法已废止）：提交成功看
  `accepted`，成交结果查 `position()`（runner 另有 `/topic/fills` WS 推送）。
- `close_position()` 无持仓时返 `OrderAck(accepted=False, reason="NO_POSITION")`（不下单）。

### 重复下单防护（runner 异步成交的标准守护）

`accepted=True` 只代表请求被受理，**不代表已成交**：runner 的成交是异步的
（PAPER 由行情推送驱动撮合、LIVE 是交易所异步回报），下单后 `position()`
不会立即刷新。按「持仓闸门 + 持续信号」直接下单，信号未消退的下一根 bar 会
**重复下单**（runner 杠杆下后果被放大）。官方模板内置的标准守护惯用法：

```python
PENDING_TIMEOUT = 3   # 调大→拒单后冻结更久、错过信号窗口;调小→成交慢于超时时双单概率上升
_PENDING_BASE = None  # 未决意向=下单时持仓基线;None 表示无未决意向
_PENDING_BARS = 0     # 意向已等待的 bar 数


def _guard_allows(pos):
    """推进未决意向状态机,返回本 bar 是否允许下单(每 bar 恰好调用一次,无信号也推进)。"""
    global _PENDING_BASE, _PENDING_BARS
    if _PENDING_BASE is None:
        return True
    _PENDING_BARS += 1
    if pos.qty != _PENDING_BASE or _PENDING_BARS >= PENDING_TIMEOUT:
        _PENDING_BASE, _PENDING_BARS = None, 0
        return True
    return False


def _mark_pending(pos):
    """下单受理后登记未决意向(基线=下单时持仓,成交确认=持仓离开基线)。"""
    global _PENDING_BASE, _PENDING_BARS
    _PENDING_BASE, _PENDING_BARS = pos.qty, 0


def on_bar(bar, ctx):
    # entry_signal / AMOUNT 为示意占位:替换为你自己的信号表达式与金额常量。
    # 注意 runner 侧 on_bar 抛异常是记 stderr 继续跑(§6):占位名忘替换不会让
    # 进程失败,而是每根 bar NameError、永不交易——回测侧则 fail-fast 报 FAILED。
    pos = ctx.position(ctx.symbol)
    tradable = _guard_allows(pos)          # 每 bar 恰好推进一次(无信号也推进)
    if entry_signal and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)             # 离场腿同理登记
        else:
            ctx.log(f"下单未受理: {ack.reason}")  # 拒单必须出声,别打成功口径日志
```

语义：下单受理后登记**下单时持仓基线**，持仓离开基线（成交确认）前拦截一切
新下单；等待满 `PENDING_TIMEOUT` 根仍未决则放行重试（订单被拒 / 被撤后的恢复
通道，否则策略永久卡死）。成交确认用 `!=` 做**方向无关**判定：SPOT 买卖、
PERP signed 做空（qty 走负）与强平归零都会离开基线——按方向比较
（`qty > base`）对 PERP 空头的**开仓腿**是永假式（0 → 负永不「大于」），
成交确认死亡、节奏退化为纯超时驱动（平仓腿负 → 0 碰巧仍能确认，别依赖这种
巧合），不要那样写。契约不提供挂单查询（`ctx` 无 open-orders 通道），持仓是
三运行时一致的可观测事实；部分成交即视为已决（离开基线），剩余量不自动补单。

**离开基线是成交的近似确认，不是等价式**：runner `position()` 查询失败会
降级为 qty=0（stderr 有 `position query failed` 可观测）——基线非零
（离场 / 加仓腿）时降级值会被当作成交确认**提前放行**（fail-open）；基线为
零（平仓态入场腿）时降级值恰等于基线，守卫阻塞至超时才放行，双单风险走的是
超时通道。外部手工交易移动同账户持仓、LIVE 部分成交后残余活单，同样会让
守卫提前放行。守护降低重复下单概率，不替代挂单可观测性。

**对回测行为的影响**（诚实声明）：回测撮合发生在次 bar 的撮合快照价
（`docs/matching-spec.md` §4/§7：FAST 市价 = last × (1 ± 滑点)，回测快照
last 取该 bar **收盘价**；撮合落账在 `on_bar` 之前），意向次 bar 即决，
**成交路径与无守护逐字节一致**（`tests/python/test_pending_guard_idiom.py`
差分锁死）。拒单路径不同：回测 `place_order` 回执恒 `accepted=True`
（`close_position()` 的 `NO_POSITION` 除外，`if ack.accepted` 天然跳过；
接受性 / 账本闸门拒单异步进报告 warnings），拒单不移动持仓，重试节奏从
每 bar 一次变为每 `PENDING_TIMEOUT` 根一次。后果分两类：拒单原因**跨 bar
恒定**（acceptance 对固定输入恒拒）时 trades / equity / metrics 不变、仅
warnings 条数与时间戳漂移；拒单原因**随价格时变**（现金 / 保证金闸门、
LIMIT 未触发过期）时，重试延后会移动首次成交 bar 与成交价，结果与无守护
版本**不再逐字节可比**——给存量策略补守卫后重跑回测出现收益差异属预期。

**适用边界**（单槽实现照抄仅限以下形态，越界须扩展状态）：

- **单标的 `on_bar` 形态**：模块级单槽基线跨 symbol 共享。组合回测
  （`on_bars` 多标的）必须改为 per-symbol 状态 dict，否则标的 A 的意向会
  拦截标的 B 的下单；且组合引擎对缺 bar 标的的挂单**结转**（等该标下一根
  可用 bar），意向可悬置多个时间轴步而 `_PENDING_BARS` 数的是时间轴步——
  数据稀疏标的下超时放行时原单可能仍在队列，重下单会两笔先后成交。
  组合场景请调大超时或先核对结转语义。
- **市价 taker 模式**：被动限价「每 bar 撤旧挂新」在**当前契约下不可实现**
  ——`OrderAck` 不携带 order id，`ctx.cancel(order_id)` 的 id 无来源
  （既有契约缺口，补齐前本条款是前瞻性边界）。即便契约补齐，本守护与报价腿
  也不兼容：挂单未成交期间守卫会持续拦截新报价（策略在行情中裸奔无挂单），
  而 `PENDING_TIMEOUT=1` 是首个 tick 即放行（拦零根，等价于报价腿完全绕过
  守卫）——不存在「降为 1 还保留部分防护」的中间态。
- **一进一出**：单槽为模板形态设计（同一时刻至多一个未决意向）；多笔并行
  挂单的策略需要 per-order 槽位。
- **on_bar 形态**：事件回调（§8）在相邻两次 on_bar 调用之间派发（回测中
  `on_fill`/`on_liquidation` 位于 bar 节点内部、该 bar 的 on_bar 之前；`on_funding`
  位于资金费节点、归属 bar 的 on_bar 之后），"每 bar 恰好调用一次 `_guard_allows`"的
  推进假设被打破——回调内下单须自管守卫状态（§8「回调内下单与异常」）。

### Position（持仓视图）

```python
symbol: str
qty: Decimal        # SPOT ≥ 0;PERP 为 signed 净持仓(正=LONG 负=SHORT)
avg_price: Decimal
# PERP 扩展(SPOT 恒 None):
leverage: int | None
margin_mode: str | None          # ISOLATED / CROSS
margin: Decimal | None           # 锁定保证金(回测;runner 侧不映射)
liquidation_price: Decimal | None  # ISOLATED 参考价(可 ≤0=资金费穿蚀)
unrealized_pnl: Decimal | None
```

回测返回账本副本（策略改不坏账本）；runner 经 REST `/positions` 实时查询
（失败降级为 qty=0 空持仓，记 stderr）。

**runner 分桶聚合声明**：paper/实盘账本是双向分桶模型（同 symbol 可同时存在 LONG/SHORT
及不同 leverage/marginMode 桶行），runner 的 `position()` 把桶行**聚合成 signed 净持仓**
（ΣLONG − ΣSHORT）对齐回测契约；方向字段取净方向主导桶（`avg_price` 按 qty 加权，
`leverage`/`margin_mode`/`liquidation_price` 取主导桶首行——桶间不同杠杆时是近似值），
`unrealized_pnl` 为全桶之和。`close_position()` **逐桶行**下 CLOSE_*（对冲态拆多笔全部
平掉，每笔携带该桶自身 leverage/marginMode；任一桶被拒则回执 `accepted=False`、reason 拼接）。

## 3. 金额类型规则（红线）

- `amount` / `price` 只收 **`Decimal` / `str` / `int`**，**float 拒**（`TypeError`）。
  float 残差（`0.1 + 0.2 = 0.30000000000000004`）会踩穿库存闸门造成"满仓平不掉"或灰尘仓。
- 行情（`history` 返回值、`bar.open/high/low/close/volume`）是 **float**——非金额，直接算术。
- 账本与回执（`qty`/`avg_price`/`equity()`/`filled_qty`…）全 **Decimal**。
- 从行情 float 推导下单量时显式转换：`amount=Decimal(str(round(qty_float, 8)))`；
  全平直接用 `ctx.close_position()`（账本原值，从根上无残差）。
- **仅 SPOT** SELL 库存闸门带 dust 容差（`docs/matching-spec.md` §7）：超出持仓 `< 1e-12`
  视为全平意图，clamp 到账本原值成交——但这只是兜底，正确姿势仍是 `close_position()`。
  PERP CLOSE 无 dust 容差（`close_position()` 取账本原值，本无残差来源；超仓一律拒）。
- REST 通道的金额字段（持仓 / 余额 / 订单回执）一律 **decimal string** 序列化，
  Python 侧 `Decimal(str(...))` 直读，不经 JSON number/float 中转。

## 4. PERP 四向语义

PERP（永续合约）下单用 `position_effect` 表达意图，**`side` 禁传**（由 effect 派生，
入口消灭双源矛盾；回测 acceptance 与 Java `Order.validate` 同一张派生表）：

| position_effect | 派生 side | 语义 |
|---|---|---|
| `OPEN_LONG` | BUY | 开多 |
| `OPEN_SHORT` | SELL | 开空 |
| `CLOSE_LONG` | SELL | 平多 |
| `CLOSE_SHORT` | BUY | 平空 |

**`amount` 单位 = 币数量（base coin）**：`0.01` 即 0.01 BTC。平台全链路币口径，策略
任何入口都不接触合约张数（张↔币换算只在交易所出站边界按 contractSize 精确整除，
除不尽 fail-closed 拒单）。

```python
def on_bar(bar, ctx):
    pos = ctx.position()                       # qty signed:正=多 负=空
    if signal_long and pos.qty <= 0:
        ctx.place_order(order_type="MARKET", amount="0.01",
                        position_effect="OPEN_LONG",
                        leverage=10, margin_mode="ISOLATED")   # 回测首仓必填
    elif exit_signal and pos.qty != 0:
        ctx.close_position()                   # 方向自动派生 CLOSE_LONG/CLOSE_SHORT
```

- **回测**：`leverage`/`margin_mode` 首仓必填，已有持仓可省略（引擎从持仓继承）；
  显式矛盾由引擎闸门拒单进 warnings。SPOT 任务传合约字段 → ValueError（规则 19 同构）。
- **runner**：未显式传时缺省用**策略级绑定**（策略创建时的 leverage/marginMode，
  经 bootstrap 下发）——不再需要把杠杆烘焙进源码。
- **穿蚀仓**：ISOLATED 仓位保证金被资金费逐期侵蚀（`margin` 可为负）；回测中穿蚀后
  主动订单一律拒（`MARGIN_DEPLETED` 进 warnings），仓位只能经强平退出。语义与偏差声明见
  [perp-backtest-spec.md](perp-backtest-spec.md) §3.3。
- 回测账本是**净持仓**模型（穿零反转自动拆平旧+开新两段），强平是 **bar 极值近似**
  （`BAR_EXTREME_APPROX`，保守偏差），资金费按已结算期次的精确时间戳事件回放（§8
  `on_funding`）——全部语义与失真清单见
  [perp-backtest-spec.md](perp-backtest-spec.md)。runner 走交易所/paper 撮合真实语义，
  两侧**不声称逐位等价**。
- 组合（多标的）回测支持 SPOT 与 PERP（PERP 为 per-symbol 净持仓、逐仓/全仓账户账本，见 [perp-backtest-spec.md](perp-backtest-spec.md) §10）。

## 5. parameters（策略参数）

任务的 `parameters`（REST `SubmitBacktestRequest.parameters` / MCP `run_backtest params` /
策略绑定 `strategies.parameters`）以两条同源通道进策略：

- **模块级 `PARAMS`**：worker 在 exec 源码**前**注入（浅冻结只读 Mapping），
  顶层常量即可取参：`FAST = int(PARAMS.get("fast", 5))`（保留默认值 = 向后兼容）；
- **`ctx.params`**：同一份数据的只读视图。

约定：键名 snake_case 由策略自定；`initial_capital` 是保留键（回测初始资金，缺省
100000）；**金额类参数必须传 JSON 字符串**（`{"amount": "0.02"}`）——JSON number 到
Python 即 float，平台**不代转**，`place_order` 直接拒 float（TypeError），策略顶层
`Decimal(str(PARAMS.get(...)))` 显式转换是唯一正确姿势。parameters 非法 JSON → 任务
fail-closed 失败（不静默降级 `{}`）。

## 6. 三运行时能力矩阵

| 维度 | 单标的回测 | 组合回测 | runner（模拟/实盘） |
|---|---|---|---|
| 入口 | `on_bar(bar, ctx)` | `on_bars(ctx)` | `on_bar(bar, ctx)` |
| 下单时机 | NEXT_BAR（下一根 bar 本地撮合） | NEXT_BAR（该标下一根可用 bar） | 实时 REST（平台风控/冻结后路由 executor） |
| symbol 参数 | 可省（绑定） | **必传**（白名单内） | 可省（绑定） |
| 做空 / PERP | PERP 单标的支持（四向）；SPOT 超卖拒 | SPOT + PERP（per-symbol 净持仓，逐仓/全仓，§10） | 全支持（交服务端裁决） |
| equity()/available_cash() | 直读引擎账本 | 直读引擎账本（共享现金池） | REST 余额合成（SPOT 用最新 bar mark-to-market；PERP 加持仓未实现盈亏；查询失败降级 0） |
| cancel | no-op（限价单单 bar 过期） | no-op | 真撤（失败吞掉记 stderr） |
| report_progress | 真上报（节流） | 真上报 | no-op |
| 回执 filled_* | 恒 None（成交在下一 bar） | 恒 None | 提交时点值（PAPER "0" / LIVE null，成交异步） |
| on_bar 抛异常 | 任务 FAILED（fail-fast，exit 1） | 任务 FAILED | 记 stderr **继续跑**（下一根 bar 照常） |
| 业务拒单去向 | 报告 warnings（拒单类上限 10 条；强平/资金费代理/末根未撮合标注不受限） | 同左 | `OrderAck(accepted=False, reason=…)` |
| 事件回调（§8） | `on_fill`/`on_liquidation`/`on_funding` 节点内**同步有序**派发 | SPOT 仅 `on_fill`；PERP 加 `on_funding`/`on_liquidation`（§10，节点内同步有序） | 三回调经 WS 推送**异步**派发（与 on_bar 无顺序保证；按绑定账户+市场类型+symbol 过滤） |
| 事件回调抛异常 | 任务 FAILED（与 on_bar 同级 fail-fast） | 任务 FAILED | 记 stderr 继续跑 |
| 预估资金费 `predicted_funding_rate()`（§9） | ✗ 抛 `NotImplementedError` | ✗ 抛 `NotImplementedError` | ✓ 仅 PERP（实时预估，回测无对应真值） |

异常语义两侧**有意不同**：回测是研究工具，策略 bug 必须炸出整个任务（防止带病出报告）；
runner 是长驻交易进程，单根 bar 的策略异常不能杀死进程（记错继续，健康信号可观测）。

## 7. 历史与暖机

- `history(field, n)` 返回最近 n 根**含当前 bar** 的字段值（`list[float]`），
  不足 n 根返已有（开头 warmup 期），field ∈ `open/high/low/close/volume`。
- 回测：全量 K 线预载，切片零请求；组合：指针只推进到 `<= 当前时间轴` 的 bar，
  **严禁未来数据**。
- runner：WS 推 bar 关闭后逐根累积；启动时预填最近 200 根已收盘 bar（消除重启失忆），
  策略声明 `WARMUP_BARS` 时按其回填（上限 999）；两通道按 openTime 去重，同区间不重复灌。

## 8. 事件回调（on_fill / on_funding / on_liquidation）

策略除 `on_bar`/`on_bars` 外可**可选定义**顶层事件回调，在成交 / 资金费结算 / 强平发生时
被调用。不定义则引擎不派发（零开销）；定义哪个派发哪个，互相独立。payload 是 frozen
dataclass（代码级真相源 `kwikquant_worker/context.py`），金额字段一律 `Decimal`、
行情语义字段与账本口径一致（§3 红线同样适用）。

```python
def on_fill(fill, ctx):        # FillEvent:每笔成交(含 LIMIT maker 成交)
    ...
def on_funding(ev, ctx):       # FundingEvent:每期资金费结算(仅 PERP;flat 期次不派发)
    ...
def on_liquidation(ev, ctx):   # LiquidationEvent:强平成交(仅 PERP;不双派 on_fill)
    ...
```

### FillEvent

```python
symbol: str
side: str                    # 回测=大写 BUY/SELL、runner=小写 buy/sell(跨运行时存量漂移,统一另批;PERP 是派生量,开平语义看 position_effect)
price: Decimal               # 成交价
qty: Decimal                 # 成交数量(币数量)
fee: Decimal
fee_currency: str            # 空串 = symbol 无合法 quote 段(不可推导),拼展示文案前先判空
filled_at: str               # ISO-8601 Z 记法
order_id: int | None         # 回测=引擎内部序号(非平台订单 id);runner=平台 orderId
liquidity: str | None        # taker/maker
position_effect: str | None  # PERP 四向(§4);SPOT 恒 None;runner 的 legacy PERP 成交行
                             # 可为 null(新订单已被接受性层 POSITION_EFFECT_REQUIRED 拦,
                             # 存量窗口极窄),开平判定对 null 需容错
```

### FundingEvent（仅 PERP）

```python
symbol: str
funding_time: str            # 精确结算时刻 ISO-8601 Z 记法(可落在 bar 中段;与 filled_at/
                             # bar.timestamp 同记法,字符串可直接对齐比较)
settled_rate: Decimal | None # 期次费率(runner LIVE 账单来源可为 None)
amount: Decimal              # 本期金额(持仓视角,正=收 负=付)
qty_at_settle: Decimal       # 结算时持仓量(|signed qty|;runner 载荷缺失时为 0——零仓本不
                             # 派发,收到事件时 0 即"数据缺失"而非零仓)
mark_price: Decimal | None   # 结算 mark 价:回测=期次行交易所真值(缺行 fallback 归属 bar
                             # close);runner WS 载荷无此字段,恒 None
source: str | None           # 回测=EXCHANGE/PROXY_BINANCE(跨所代理期次);runner 恒 None
```

回测结算语义（归属 / 时点 / mark 真值化）单一真相源是
[perp-backtest-spec.md](perp-backtest-spec.md) §5。

### LiquidationEvent（仅 PERP）

```python
symbol: str
timestamp: str
position_side: str           # 被平方向 LONG/SHORT
qty: Decimal                 # 强平数量(绝对值;回测=全平量,runner=本次实际平仓量,
                             # 并发加仓边缘场景可小于触发时持仓,见 ws-contract 3.9)
price: Decimal | None        # 强平成交价(回测=bar 极值近似价恒有值,perp-backtest-spec §4;
                             # runner=liquidationPrice,派生未算出时 null)
realized_pnl: Decimal | None # 强平损益(回测=净额含 fee;runner=该持仓已实现盈亏)
margin_mode: str | None      # ISOLATED/CROSS(runner legacy 桶行可空)
reason: str | None           # runner=触发原因文案;回测恒 None(近似模型声明在报告层)
```

强平成交**不双派 `on_fill`**（两侧同构：runner 的 `/topic/liquidations` 与
`/topic/fills` 通道互斥，回测对齐）。

### 派发时序（回测与 runner 的有意差异）

| | 回测 | runner |
|---|---|---|
| 来源 | 引擎时间轴节点（bar 处理包内同步派发） | WS `/topic/fills\|liquidations\|funding/{userId}` 推送 |
| 顺序保证 | **有**：`on_liquidation` → 撮合 → `on_fill`(逐笔) → `on_bar` → `on_funding`(逐期) | **无**：跨 topic 到达顺序不保证，`on_fill` 与 `on_bar` 可交错 |
| 线程模型 | 引擎循环单线程同步 | **串行 + 同一工作线程**（单线程 executor 结构保证，与回测单线程语义对齐——模块级状态无并发交错，`threading.local` 可用）；回调不在 asyncio 事件循环线程 |
| 过滤 | 单标的天然只有本 symbol | user 级 topic 推该用户**全部账户、全部标的**的事件，worker 按绑定 **accountId + 市场类型 + symbol** 过滤后才派发（防 PAPER/LIVE 跨账户泄漏进回调与同账户 SPOT/PERP 同 symbol 串扰；canonical 形态两侧一致，`BTC/USDT`，PERP 无 `:结算币` 后缀；不匹配限次记 stderr；载荷缺 accountId/marketType 时该层过滤降级放行——旧后端版本偏斜容忍） |
| 金额字段 | 引擎 Decimal 原值 | WS 载荷是 JSON number（已知契约缺口），worker 经 `parse_float=Decimal` + `Decimal(str(v))` 防御性转换后进 payload，不经 float 运算 |
| 送达保证 | 引擎逐节点派发，不丢失 | `on_fill`：**进程内 exactly-once**——断线窗口由周期增量补拉兜底（REST `GET /api/v1/worker/fills-since`，fillId 去重；延迟上界 = 轮询周期 60s + 服务端提交安全边界 2s；**重启不回放**，进程重启窗口的缺口归对账契约；补拉游标停摆窗（REST 故障而直播照常）内直播认领达去重集半容量时同样整窗放弃并重播种——防升序重扫与去重集 FIFO 淘汰锁步成重复风暴，WARN 出声，窗口缺口归对账契约）。`on_funding`/`on_liquidation`：断线窗口内**永久丢失**（无补拉通道） |

**策略写法约束**：不要依赖 `on_fill` 与 `on_bar` 的相对顺序（回测有序、runner 无序——
依赖顺序的策略回测通过、实盘竞态）。持仓状态以 `ctx.position()` 为准，事件回调用于
感知与响应（记日志、更新自维护状态、触发下单），不是状态同步的唯一通道；runner 的
`on_funding`/`on_liquidation` 断线窗口丢失、`on_fill` 的重启窗口缺口（重启不回放）与
补拉延迟（最长约 1 分钟）都会让纯事件驱动的自维护状态静默漂移——须周期性用
`ctx.position()` / REST 对账兜底。回调内 `ctx.equity()` 与 `position().unrealized_pnl` 同用当前 bar close
口径（恒等式 `equity = cash + unrealized` 在回调内成立；runner 侧 equity 走 REST 实时
查询，语义见 §6 矩阵）。

### 回调内下单与异常

- 回调内 `ctx.place_order` 与 `on_bar` 内同语义：回测进同一意图队列 **NEXT_BAR** 撮合
  （`matching-spec.md` §7）；runner 实时 REST。
- 异常语义与 `on_bar` 同级（§6 矩阵）：回测 fail-fast 整个任务 FAILED；runner 记 stderr
  继续跑。
- 「重复下单防护」单槽守卫（§2）**只覆盖 on_bar 形态**：事件回调在相邻两次 on_bar 调用
  之间派发（回测中 `on_fill`/`on_liquidation` 位于 bar 节点内部、该 bar 的 on_bar 之前；
  `on_funding` 位于资金费节点、归属 bar 的 on_bar 之后），`_guard_allows` 的
  "每 bar 恰好一次"推进假设被打破。在事件回调里下单的策略需要 per-回调槽位或统一
  委托一个下单函数管理守卫状态。

## 9. 预估资金费查询（仅 runner）

```python
ctx.predicted_funding_rate(symbol=None) -> Decimal | None
```

- **仅 runner 提供**；单标的回测与组合回测调用抛 `NotImplementedError`（"预估资金费仅 runner 可用"）——
  这是三运行时**有意的能力分叉**，差分测试显式锁定（不是遗漏）。
- 返回绑定标的（或显式 `symbol`）**当前期次的预估资金费率**（`Decimal`，带符号；正=多头付空头收，
  OKX 语义）。数据不可得（交易所无预估 / 网络失败 / 非 PERP）时返 `None`——与 `position()` 查询失败
  同纪律，**不抛不猜、绝不静默造值**。SPOT runner 调用返 `None`（无资金费概念）。
- 数据源：runner 经 worker REST 端点实时取交易所预估（市场模块短 TTL 缓存），**不读回测已结算序列**。

### lookahead 契约（回测 / 实盘的已知差异，不声称等价）

预估资金费是**"指向未来结算时刻的当期累计值"**：`funding_time` 是未来的结算时刻，费率随期内滚动累积、
结算时冻结。把它喂进回测就是 lookahead bias——它已编码了整期相对任意期中 bar 的未来结果。因此：

- **回测侧**（含组合）无预估通道，`on_funding` 与账本只用**已结算**费率（[perp-backtest-spec.md](perp-backtest-spec.md) §5）。
  用资金费 carry 的策略在回测里的 carry 信号**滞后一期**（本期只能看到上期已结算值）——这是**已知近似**，不修正。
- **runner 侧**可读预估，构成回测与实盘的**已知差异**：同一份策略源码，回测里 carry 滞后一期、runner 里
  能拿到当期预估，两侧的下单时点与成交因此**不同**。平台**不声称两侧等价**——用 `predicted_funding_rate()`
  的策略必须自行承担这一差异（回测结论不能直接外推到实盘的预估驱动行为）。
- 正确姿势：把 `predicted_funding_rate()` 的返回值当**实盘增量信息**，而非回测可复现的信号；回测里做保守版
  （用已结算滞后值），runner 里叠加预估——并清楚两条路径不是同一策略的等价实现。

### 跨运行时安全写法（一份 `on_bar` 同时跑回测与 runner）

单标的策略回测与 runner **共用同一个 `on_bar(bar, ctx)`**。直接 `rate = ctx.predicted_funding_rate()` 会在
**回测里抛 `NotImplementedError` → 任务 FAILED**（§6：回测回调异常 fail-fast）。**注意非对称**：SPOT runner 返
`None`（不抛），但**回测是 raise**——所以 `if rate is not None:` 只挡得住 runner 的 None、挡不住回测的异常。
契约不提供 `is_runner()` 之类运行时判别，正确守法是 `try/except NotImplementedError` 归一为 `None`：

```python
def _predicted_or_none(ctx):
    """回测无预估(NotImplementedError)、runner 数据不可得(None)统一降级为 None,一份代码通吃。"""
    try:
        return ctx.predicted_funding_rate()   # runner:Decimal 或 None;回测/组合:抛 NotImplementedError
    except NotImplementedError:
        return None

def on_bar(bar, ctx):
    predicted = _predicted_or_none(ctx)        # 回测恒 None → 走保守 carry 分支
    if predicted is not None and predicted < 0:
        ...                                    # 仅 runner 命中:预估负费率(空头收),叠加实盘增量信号
```

回测里 `predicted` 恒 `None`，carry 逻辑自然退回"只用已结算滞后值"的保守版；runner 里才拿到当期预估。
两侧行为差异是**设计使然**（上文 lookahead 契约），不是 bug——不要为了"回测也能用预估"去绕过它。
