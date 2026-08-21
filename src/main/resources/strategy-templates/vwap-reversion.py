"""滚动 VWAP 偏离回归:价格偏离成交量加权均价过大时反向做,回归后离场。

逻辑:以最近 WINDOW 根的 VWAP(典型价按成交量加权)为公允价锚。收盘价
低于 VWAP 超过 BAND 视为超跌(买),高于 VWAP 超过 BAND 视为超涨(有仓则卖)。
成交量加权使锚点偏向真实成交密集区,比简单均线更贴近市场成本。
适合高流动性标的的震荡行情;单边行情中偏离会持续扩大,需风控兜底。

可调常量:WINDOW VWAP 窗口,BAND 偏离阈值(比例),AMOUNT 每笔下单量。
"""
WINDOW = 48       # VWAP 滚动窗口(15m × 48 = 12 小时)
BAND = 0.008      # 偏离 0.8% 触发
AMOUNT = 0.01     # 每笔下单量(BTC)


def _rolling_vwap(highs, lows, closes, volumes):
    pv, vol = 0.0, 0.0
    for i in range(len(closes)):
        typical = (highs[i] + lows[i] + closes[i]) / 3.0
        pv += typical * volumes[i]
        vol += volumes[i]
    return pv / vol if vol > 0 else closes[-1]


def on_bar(bar, ctx):
    highs = ctx.history("high", WINDOW)
    lows = ctx.history("low", WINDOW)
    closes = ctx.history("close", WINDOW)
    volumes = ctx.history("volume", WINDOW)
    if len(closes) < WINDOW:
        return
    vwap = _rolling_vwap(highs, lows, closes, volumes)
    deviation = (bar.close - vwap) / vwap
    pos = ctx.position(ctx.symbol)
    if deviation < -BAND and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"低于 VWAP 做多 deviation={deviation:.2%} vwap={vwap:.2f}")
    elif deviation > BAND and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"高于 VWAP 平仓 deviation={deviation:.2%} vwap={vwap:.2f}")
