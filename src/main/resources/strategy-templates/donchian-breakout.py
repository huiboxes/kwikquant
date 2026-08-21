"""唐奇安通道突破:收盘价突破前 N 根最高价做多,跌破前 M 根最低价平仓。

逻辑:经典 CTA 趋势跟踪(海龟交易法同源)。入场用较长窗口 N 防假突破,
离场用较短窗口 M 保利润(让利润奔跑、亏损尽快离场)。
突破策略胜率低但盈亏比高,依赖少数大趋势覆盖多次小止损。

可调常量:ENTRY_WINDOW 入场窗口,EXIT_WINDOW 离场窗口,AMOUNT 每笔下单量。
"""
ENTRY_WINDOW = 20  # 入场:突破前 19 根最高(不含当前 bar,避免自指)
EXIT_WINDOW = 10   # 离场:跌破前 9 根最低
AMOUNT = 0.01      # 每笔下单量(BTC)


def on_bar(bar, ctx):
    highs = ctx.history("high", ENTRY_WINDOW)
    lows = ctx.history("low", EXIT_WINDOW)
    if len(highs) < ENTRY_WINDOW or len(lows) < EXIT_WINDOW:
        return
    n_high = max(highs[:-1])
    n_low = min(lows[:-1])
    pos = ctx.position(ctx.symbol)
    if bar.close > n_high and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"突破做多 close={bar.close:.2f} > {n_high:.2f}")
    elif bar.close < n_low and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"跌破平仓 close={bar.close:.2f} < {n_low:.2f}")
