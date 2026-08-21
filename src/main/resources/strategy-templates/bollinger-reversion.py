"""布林带均值回归:触及下轨做多,触及上轨平仓(赌价格回归中轨)。

逻辑:布林带 = MA(PERIOD) ± MULT 倍标准差。价格触及下轨视为超跌,
博反弹做多;触及上轨视为超涨,获利离场。统计上价格多数时间留在带内,
适合震荡市;趋势突破行情会连续贴轨运行,需风控兜底。

可调常量:PERIOD 均线周期,MULT 带宽倍数,AMOUNT 每笔下单量。
"""
PERIOD = 20     # 均线周期
MULT = 2.0      # 带宽 = MULT × 标准差
AMOUNT = 0.01   # 每笔下单量(BTC)


def on_bar(bar, ctx):
    closes = ctx.history("close", PERIOD)
    if len(closes) < PERIOD:
        return
    ma = sum(closes) / PERIOD
    variance = sum((c - ma) ** 2 for c in closes) / PERIOD
    std = variance**0.5
    upper = ma + MULT * std
    lower = ma - MULT * std
    pos = ctx.position(ctx.symbol)
    if bar.close <= lower and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"触及下轨做多 close={bar.close:.2f} lower={lower:.2f}")
    elif bar.close >= upper and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"触及上轨平仓 close={bar.close:.2f} upper={upper:.2f}")
