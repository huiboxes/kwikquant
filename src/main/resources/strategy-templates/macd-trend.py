"""MACD 趋势跟踪:DIF 上穿信号线(DEA)做多,下穿平仓。

逻辑:MACD 由快慢 EMA 之差(DIF)与其信号线(DEA)组成。DIF 上穿 DEA
(金叉)视为动能转强入场,下穿(死叉)离场。比单一均线交叉滞后更小、
噪音更少,适合中等周期趋势行情;震荡市同样会产生交叉磨损。

可调常量:FAST/SLOW EMA 周期,SIGNAL 信号线周期,AMOUNT 每笔下单量。
"""
FAST = 12      # 快线 EMA
SLOW = 26      # 慢线 EMA
SIGNAL = 9     # 信号线 EMA
AMOUNT = 0.01  # 每笔下单量(BTC)


def _ema_series(values, period):
    k = 2.0 / (period + 1)
    out = [values[0]]
    for v in values[1:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


def _macd(closes):
    """返回 (dif, dea) 序列对(与 closes 等长)。"""
    fast = _ema_series(closes, FAST)
    slow = _ema_series(closes, SLOW)
    dif = [f - s for f, s in zip(fast, slow)]
    dea = _ema_series(dif, SIGNAL)
    return dif, dea


def on_bar(bar, ctx):
    warmup = SLOW + SIGNAL
    closes = ctx.history("close", warmup)
    if len(closes) < warmup:
        return
    dif, dea = _macd(closes)
    pos = ctx.position(ctx.symbol)
    # 金叉:上一 bar DIF<=DEA 且当前 DIF>DEA;死叉反之
    crossed_up = dif[-2] <= dea[-2] and dif[-1] > dea[-1]
    crossed_down = dif[-2] >= dea[-2] and dif[-1] < dea[-1]
    if crossed_up and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"MACD 金叉做多 dif={dif[-1]:.2f} dea={dea[-1]:.2f}")
    elif crossed_down and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"MACD 死叉平仓 dif={dif[-1]:.2f} dea={dea[-1]:.2f}")
