/**
 * 预置策略模版(用户快速回测 / 当作起点)。函数式 on_bar(bar, ctx) 风格,
 * 用 ctx.history/position/place_order/log(平台核心,纯标准库)。
 *
 * 策略 API 约定(回测/实盘统一):ctx.place_order() 返 Fill(提交信息,**回测同步 qty>0,
 * 实盘异步 qty=0** 订单 NEW/部分成交);**查 ctx.position(symbol).qty 判成交**(回测/实盘一致),
 * 不依赖 place_order 返回值的 qty(见 preset 全用 pos.qty 判持仓)。止损止盈靠交易所条件单
 * (OKX stop-limit/OCO,on_bar 内 ctx.place_order 下条件单),不依赖 on_tick。
 *
 * SPOT 预设可回测;PERP 预设仅实盘/模拟运行(回测 BacktestOrderService 拒 PERP,planned for phase 6+)。
 * amount/price 用户传 float/str,边界 _bd 转 Decimal;行情 open/high/low/close/volume 是 float(非金额)。
 * PERP 预设的 on_bar 内 place_order 传 leverage/margin_mode/position_effect(合约四向:OPEN_LONG/
 * OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT),查 pos.qty 判合约持仓(非现货)。
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
    key: 'double-cross-perp',
    name: '均线双金叉 · 合约',
    description: 'PERP 开多/平多(OPEN_LONG/CLOSE_LONG),leverage=10 逐仓。仅实盘/模拟运行,暂不支持回测',
    defaultSymbol: 'BTC/USDT',
    defaultInterval: '1h',
    sourceCode: DOUBLE_CROSS_PERP,
    marketType: 'PERP',
  },
]
