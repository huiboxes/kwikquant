"""Dual Thrust 区间突破:价格冲上锚点+K1×区间幅度做多,跌破锚点-K2×幅度平仓。

逻辑:经典日内突破策略。以近 N 根的区间幅度 Range = max(HH-LC, HC-LL)
衡量波动能量,收盘价突破"前一收盘 ± K×Range"通道视为真突破。现货简化:
原版做空腿改为平仓离场。K1<K2 时入场更积极、离场更保守(追涨稳守)。

可调常量:LOOKBACK 区间窗口,K1 入场系数,K2 离场系数,AMOUNT 每笔下单量。
"""
LOOKBACK = 20  # 区间统计窗口
K1 = 0.5       # 入场系数(越小越激进)
K2 = 0.5       # 离场系数
AMOUNT = 0.01  # 每笔下单量(BTC)


def on_bar(bar, ctx):
    highs = ctx.history("high", LOOKBACK)
    lows = ctx.history("low", LOOKBACK)
    closes = ctx.history("close", LOOKBACK + 1)
    if len(highs) < LOOKBACK or len(closes) < LOOKBACK + 1:
        return
    hh = max(highs[:-1])   # 前 N 根最高
    ll = min(lows[:-1])    # 前 N 根最低
    hc = max(closes[:-1])  # 前 N 根收盘最高
    lc = min(closes[:-1])  # 前 N 根收盘最低
    range_width = max(hh - lc, hc - ll)
    anchor = closes[-2]    # 锚点:前一收盘
    pos = ctx.position(ctx.symbol)
    if bar.close > anchor + K1 * range_width and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"DualThrust 突破做多 close={bar.close:.2f} range={range_width:.2f}")
    elif bar.close < anchor - K2 * range_width and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"DualThrust 跌破平仓 close={bar.close:.2f}")
