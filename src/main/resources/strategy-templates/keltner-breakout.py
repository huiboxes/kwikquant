"""肯特纳通道突破:收盘突破 EMA+K×ATR 做多,跌回 EMA 下方平仓。

逻辑:肯特纳通道 = EMA(PERIOD) ± MULT×ATR(ATR_WINDOW)。相比布林带用
标准差,ATR 度量真实波幅对跳空/长影线更稳健,通道更平滑。突破上轨视为
波动放大的趋势起点入场;价格回落至中轨(EMA)下方说明动量消退离场。

可调常量:PERIOD EMA 周期,ATR_WINDOW ATR 周期,MULT 通道宽度,AMOUNT 每笔下单量。
"""
PERIOD = 20       # 中轨 EMA 周期
ATR_WINDOW = 10   # ATR 周期
MULT = 2.0        # 通道宽度倍数
AMOUNT = 0.01     # 每笔下单量(BTC)


def _ema_series(values, period):
    k = 2.0 / (period + 1)
    out = [values[0]]
    for v in values[1:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


def _atr(highs, lows, closes, period):
    trs = []
    for i in range(1, len(highs)):
        tr = max(
            highs[i] - lows[i],
            abs(highs[i] - closes[i - 1]),
            abs(lows[i] - closes[i - 1]),
        )
        trs.append(tr)
    return sum(trs[-period:]) / period


def on_bar(bar, ctx):
    warmup = max(PERIOD, ATR_WINDOW + 1)
    highs = ctx.history("high", warmup)
    lows = ctx.history("low", warmup)
    closes = ctx.history("close", warmup)
    if len(highs) < warmup or len(lows) < warmup:
        return
    ema = _ema_series(closes, PERIOD)
    atr = _atr(highs, lows, closes, ATR_WINDOW)
    upper = ema[-1] + MULT * atr
    pos = ctx.position(ctx.symbol)
    if bar.close > upper and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"突破上轨做多 close={bar.close:.2f} upper={upper:.2f}")
    elif bar.close < ema[-1] and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"跌回中轨平仓 close={bar.close:.2f} ema={ema[-1]:.2f}")
