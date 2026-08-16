/**
 * 预置策略模版(用户快速回测 / 当作起点)。函数式 on_bar(bar, ctx) 风格,
 * 用 ctx.history/position/place_order/log(平台核心,纯标准库)。
 *
 * 策略 API 约定(回测/实盘统一):ctx.place_order() 返 Fill(提交信息,**回测同步 qty>0,
 * 实盘异步 qty=0** 订单 NEW/部分成交);**查 ctx.position(symbol).qty 判成交**(回测/实盘一致),
 * 不依赖 place_order 返回值的 qty(见 preset 全用 pos.qty 判持仓)。止损止盈靠交易所条件单
 * (OKX stop-limit/OCO,on_bar 内 ctx.place_order 下条件单),不依赖 on_tick。
 *
* SPOT 预设可回测;PERP 预设仅实盘/模拟运行(回测撮合层已支持 PERP 最小方案,但提交层
 * BacktestTaskService 仍拒 PERP 策略,待 ctx 透传 positionEffect 后开放)。
 * amount/price 用户传 float/str,边界 _bd 转 Decimal;行情 open/high/low/close/volume 是 float(非金额)。
 * PERP 预设的 on_bar 内 place_order 传 leverage/margin_mode/position_effect(合约四向:OPEN_LONG/
 * OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT),查 pos.qty 判合约持仓(非现货)。
 * ctx.cancel(order_id):撤销挂单(回测 no-op——未成交限价单单根 bar 自动过期;实盘 DELETE 撤单,
 * 被动限价策略每根 bar 撤旧挂新用)。模块级常量 WARMUP_BARS = N:实盘/模拟启动时回填最近 N 根
 * 已关闭 K 线到 history(回测天然全量预载,此常量仅影响实盘/模拟;不声明则启动后从零累积)。
 */

export interface PresetStrategy {
  key: string
  name: string
  description: string
  defaultSymbol: string
  defaultInterval: string
  sourceCode: string
  /** 市场类型,默认 SPOT;PERP 预设标 'PERP',CreateStrategyDialog 选中后切 PERP 标的 + 下单字段。 */
  marketType?: 'SPOT' | 'PERP'
}

const DOUBLE_CROSS = `"""均线双金叉:MA5 上穿 MA10 且 MA10>MA20(双金叉)做多;死叉平仓。

经典趋势确认:单一金叉噪音大,要求快线穿上 + 中线在慢线上方(双重确认)。
"""
def on_bar(bar, ctx):
    closes = ctx.history("close", 20)
    if len(closes) < 20:
        return
    ma5 = sum(closes[-5:]) / 5
    ma10 = sum(closes[-10:]) / 10
    ma20 = sum(closes[-20:]) / 20
    pos = ctx.position(ctx.symbol)
    # 双金叉:MA5>MA10(快穿上)且 MA10>MA20(趋势向上)
    if ma5 > ma10 and ma10 > ma20 and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=0.01)
        ctx.log(f"双金叉做多 ma5={ma5:.2f} ma10={ma10:.2f} ma20={ma20:.2f}")
    elif ma5 < ma10 and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"死叉平仓 ma5={ma5:.2f} ma10={ma10:.2f}")
`

const CTA_BREAKOUT = `"""CTA 趋势跟踪:突破前 N 根高点做多,跌破前 N 根低点平仓。

唐奇安通道(Donchian)突破:close > 前 19 根最高 → 入场;< 前 19 根最低 → 离场。
"""
def on_bar(bar, ctx):
    highs = ctx.history("high", 20)
    lows = ctx.history("low", 20)
    if len(highs) < 20:
        return
    n_high = max(highs[:-1])  # 前 19 根最高(不含当前 bar,避免自指)
    n_low = min(lows[:-1])
    pos = ctx.position(ctx.symbol)
    if bar.close > n_high and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=0.01)
        ctx.log(f"突破做多 close={bar.close:.2f} > {n_high:.2f}")
    elif bar.close < n_low and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"跌破平仓 close={bar.close:.2f} < {n_low:.2f}")
`

const FIXED_GRID = `"""固定网格:围绕 50 根均线基线,每 1% 一格,下穿一格买、上穿一格卖。

震荡市获利:价格在基线上下波动时低买高卖。仓位上限 5 格防单边累积。
"""
GRID_STEP = 0.01   # 每格 1%
AMOUNT = 0.01      # 每格下单量(BTC)
MAX_POS = 0.05     # 最多持 5 格

def on_bar(bar, ctx):
    closes = ctx.history("close", 50)
    if len(closes) < 50:
        return
    baseline = sum(closes) / len(closes)
    # 当前价位落在第几格(相对基线,1% 一档)
    level = round((bar.close - baseline) / (baseline * GRID_STEP))
    prev_closes = ctx.history("close", 2)
    if len(prev_closes) < 2:
        return
    prev_level = round((prev_closes[0] - baseline) / (baseline * GRID_STEP))
    pos = ctx.position(ctx.symbol)
    # 下穿一格 → 买一格(低位吸筹);上穿一格 → 卖一格(高位派发)
    if level < prev_level and pos.qty < MAX_POS:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"网格买入 level={level} baseline={baseline:.2f}")
    elif level > prev_level and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"网格卖出 level={level} pos={pos.qty}")
`

const DOUBLE_CROSS_PERP = `"""均线双金叉 PERP:MA5 上穿 MA10 且 MA10>MA20 开多;死叉平多(合约双向持仓示例)。

合约版:leverage=10 ISOLATED 逐仓。开多 OPEN_LONG,死叉平多 CLOSE_LONG(本示例只做多,
做空 OPEN_SHORT/CLOSE_SHORT 留扩展)。pos.qty 是合约持仓(非现货),用 ctx.position 查。
"""
def on_bar(bar, ctx):
    closes = ctx.history("close", 20)
    if len(closes) < 20:
        return
    ma5 = sum(closes[-5:]) / 5
    ma10 = sum(closes[-10:]) / 10
    ma20 = sum(closes[-20:]) / 20
    pos = ctx.position(ctx.symbol)
    # 双金叉开多(OPEN_LONG):MA5>MA10 且 MA10>MA20
    if ma5 > ma10 and ma10 > ma20 and pos.qty <= 0:
        ctx.place_order(
            side="BUY", order_type="MARKET", amount=0.001,
            leverage=10, margin_mode="ISOLATED", position_effect="OPEN_LONG",
        )
        ctx.log(f"双金叉开多 ma5={ma5:.2f} ma10={ma10:.2f}")
    # 死叉平多(CLOSE_LONG):MA5<MA10
    elif ma5 < ma10 and pos.qty > 0:
        ctx.place_order(
            side="SELL", order_type="MARKET", amount=pos.qty,
            leverage=10, margin_mode="ISOLATED", position_effect="CLOSE_LONG",
        )
        ctx.log(f"死叉平多 ma5={ma5:.2f} ma10={ma10:.2f}")
`

const DIP_LIQUIDITY = `"""趋势内回落承接(流动性提供策略)

原理:为急迫的卖方提供即时性,收取流动性补偿(Nagel 2012;加密市场实证 Kozlowski 2020)。
结构:只在上升趋势(EMA50>EMA200)里,在 24h 均价锚下方深处挂限价买单承接恐慌回落,
价格回归锚附近收割。本质是做市的退化形态——用 K 线数据模拟被动挂单。

成本纪律(高频策略的生死线):全程限价单,市价仅用于止损/超时离场。回测的触及成交
对被动单系统性偏乐观(实盘中买单恰在继续跌穿时成交),实盘期望按回测 7 折计。
负偏警示:多数时间赚小钱,极端行情会接到继续下跌的货——趋势过滤与超时退出就是
为此设的,不要绕过它们去"优化"胜率。

默认 15m BTC/USDT,平均每天约 1-3 笔;想更频繁把 Z_ENTER 调小(单笔 edge 同步变薄),
或对多个交易对各开一个实例(频率与分散双收)。
"""

# ---------- 参数(直接改这里;平台暂不支持外部参数注入) ----------
CAPITAL_USDT = 10000.0           # 策略预算:平台读不到账户余额,按你的实际预算改
TREND_FAST, TREND_SLOW = 50, 200  # 趋势过滤 EMA 对(15m≈12.5h/50h)
ANCHOR_WINDOW = 96               # 均价锚窗口(15m×96=24h)
Z_ENTER = 1.25                   # 承接深度(σ 倍数):调小更频繁,调大单笔更厚
RISK_FRACTION = 0.30             # 单笔预算占比
MAX_HOLD_BARS = 96               # 超时退出(24h):库存不能变老
HISTORY = 600                    # 所需历史 bar(EMA200 种子收敛)
WARMUP_BARS = 640                # 实盘/模拟启动回填根数(回测无影响;略大于 HISTORY)
MIN_ORDER_USDT = 10.0            # 最小下单名义,避免尘埃单

_pending_id = None               # 实盘挂单追踪(每根 bar 先撤再挂)
_hold_bars = 0


def _ema(values, span):
    a = 2.0 / (span + 1.0)
    e = values[0]
    for v in values[1:]:
        e = a * v + (1.0 - a) * e
    return e


def on_bar(bar, ctx):
    global _pending_id, _hold_bars
    closes = ctx.history("close", HISTORY)
    if len(closes) < HISTORY:
        return  # warmup 期不交易(EMA 未收敛)
    uptrend = _ema(closes, TREND_FAST) > _ema(closes, TREND_SLOW)
    win = closes[-ANCHOR_WINDOW:]
    anchor = sum(win) / len(win)
    rets = [win[i] / win[i - 1] - 1.0 for i in range(1, len(win)) if win[i - 1] > 0]
    mean_r = sum(rets) / len(rets)
    sigma = (sum((r - mean_r) ** 2 for r in rets) / (len(rets) - 1)) ** 0.5
    if sigma <= 0:
        return
    pos = ctx.position(ctx.symbol)

    # 挂单纪律:每根 bar 先撤上一根未成交挂单(回测里未成交单自动过期,cancel 为 no-op)
    if _pending_id is not None:
        ctx.cancel(_pending_id)
        _pending_id = None

    if pos.qty > 0:
        _hold_bars += 1
        if not uptrend or _hold_bars >= MAX_HOLD_BARS:
            ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
            ctx.log(f"离场:趋势破坏或超时 hold={_hold_bars}")
            _hold_bars = 0
        elif bar.close >= anchor:
            # 回归锚上方:按信号收盘价挂限价收割(被动;下根 bar 触及成交)
            f = ctx.place_order(side="SELL", order_type="LIMIT",
                                amount=pos.qty, price=round(bar.close, 1))
            _pending_id = f.order_id if f and f.qty == 0 else None
            if f and f.qty > 0:
                _hold_bars = 0
        return

    if not uptrend:
        return  # 下跌趋势不接刀,空仓等待

    # 承接价:锚下方 Z_ENTER 个 σ;若已跌穿,在当前价下方 0.3σ 等继续下探(保持被动)
    depth = anchor * (1.0 - Z_ENTER * sigma)
    limit = depth if depth < bar.close else bar.close * (1.0 - 0.3 * sigma)
    vol_scalar = min(1.5, 0.0025 / sigma)  # 波动大→仓位小(15m σ 基准 0.25%)
    qty = CAPITAL_USDT * RISK_FRACTION * vol_scalar / limit
    if qty * limit < MIN_ORDER_USDT:
        return
    # 价格精度按 symbol tick 调整(BTC/USDT=0.1;山寨需更多小数位)
    f = ctx.place_order(side="BUY", order_type="LIMIT",
                        amount=round(qty, 6), price=round(limit, 1))
    if f and f.qty > 0:
        ctx.log(f"承接成交 @{limit:.1f} anchor={anchor:.1f} sigma={sigma:.4f}")
        _hold_bars = 0
    elif f:
        _pending_id = f.order_id
`

/** 预置策略列表(快速回测 / 模版起点)。 */
export const PRESET_STRATEGIES: PresetStrategy[] = [
  {
    key: 'double-cross',
    name: '均线双金叉',
    description: 'MA5 上穿 MA10 且 MA10>MA20 做多,死叉平仓(双金叉趋势确认)',
    defaultSymbol: 'BTC/USDT',
    defaultInterval: '1h',
    sourceCode: DOUBLE_CROSS,
  },
  {
    key: 'cta-breakout',
    name: 'CTA 趋势跟踪',
    description: '唐奇安通道突破:close 突破前 19 根高点做多,跌破低点平仓',
    defaultSymbol: 'BTC/USDT',
    defaultInterval: '4h',
    sourceCode: CTA_BREAKOUT,
  },
  {
    key: 'fixed-grid',
    name: '固定网格',
    description: '围绕 50 均线基线每 1% 一格,下穿买上穿卖(震荡市低买高卖)',
    defaultSymbol: 'BTC/USDT',
    defaultInterval: '1h',
    sourceCode: FIXED_GRID,
  },
  {
    key: 'dip-liquidity',
    name: '趋势内回落承接',
    description:
      '上升趋势中在 24h 均价下方深处挂买单承接恐慌回落,回归均价收割(流动性提供溢价,15m 限价,平均每天约 1-3 笔)',
    defaultSymbol: 'BTC/USDT',
    defaultInterval: '15m',
    sourceCode: DIP_LIQUIDITY,
  },
  {
    key: 'double-cross-perp',
    name: '均线双金叉 · 合约',
    description: 'PERP 开多/平多(OPEN_LONG/CLOSE_LONG),leverage=10 逐仓。仅实盘/模拟运行,暂不支持回测',
    defaultSymbol: 'BTC/USDT',
    defaultInterval: '1h',
    sourceCode: DOUBLE_CROSS_PERP,
    marketType: 'PERP',
  },
]
