"""短周期均线动量(1 分钟):EMA9 上穿 EMA21 做多,下穿平仓(日内高频信号)。

逻辑:1m 级别的双 EMA 交叉,捕捉日内短促动量段。信号频率高、单次盈亏小,
手续费与滑点占比大(回测已计 taker 费),适合验证执行链路或作为高频
信号骨架二次开发。趋势短暂且噪音大,实盘前务必先用回测核对费用敏感性。

可调常量:FAST/SLOW EMA 周期,AMOUNT 每笔下单量。
"""
FAST = 9       # 快线 EMA
SLOW = 21      # 慢线 EMA
AMOUNT = 0.005  # 每笔下单量(BTC,小额高频)


def _ema_series(values, period):
    k = 2.0 / (period + 1)
    out = [values[0]]
    for v in values[1:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


def on_bar(bar, ctx):
    closes = ctx.history("close", SLOW + 1)
    if len(closes) < SLOW + 1:
        return
    fast = _ema_series(closes, FAST)
    slow = _ema_series(closes, SLOW)
    pos = ctx.position(ctx.symbol)
    crossed_up = fast[-2] <= slow[-2] and fast[-1] > slow[-1]
    crossed_down = fast[-2] >= slow[-2] and fast[-1] < slow[-1]
    if crossed_up and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"1m 金叉做多 fast={fast[-1]:.2f} slow={slow[-1]:.2f}")
    elif crossed_down and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"1m 死叉平仓 fast={fast[-1]:.2f} slow={slow[-1]:.2f}")
