"""RSI 超卖反转:RSI 跌破超卖线做多,升破超买线平仓(均值回归)。

逻辑:RSI(相对强弱指数)衡量近 PERIOD 根涨跌动能。RSI<OVERSOLD 视为
超卖反弹机会入场;RSI>OVERBOUGHT 视为超买离场。适合区间震荡行情,
单边下跌中超卖可以更超卖(建议配合风控日亏限额)。此处用简单平均 RSI。

可调常量:PERIOD RSI 周期,OVERSOLD/OVERBOUGHT 阈值,AMOUNT 每笔下单量。
"""
PERIOD = 14      # RSI 周期
OVERSOLD = 30    # 超卖线
OVERBOUGHT = 70  # 超买线
AMOUNT = 0.01    # 每笔下单量(BTC)


def _rsi(closes, period):
    gains, losses = 0.0, 0.0
    for i in range(1, len(closes)):
        change = closes[i] - closes[i - 1]
        if change > 0:
            gains += change
        else:
            losses -= change
    if losses == 0:
        return 100.0
    rs = gains / losses
    return 100.0 - 100.0 / (1.0 + rs)


def on_bar(bar, ctx):
    closes = ctx.history("close", PERIOD + 1)
    if len(closes) < PERIOD + 1:
        return
    rsi = _rsi(closes, PERIOD)
    pos = ctx.position(ctx.symbol)
    if rsi < OVERSOLD and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"RSI 超卖做多 rsi={rsi:.1f}")
    elif rsi > OVERBOUGHT and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"RSI 超买平仓 rsi={rsi:.1f}")
