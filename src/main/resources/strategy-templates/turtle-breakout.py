"""海龟突破(日线):突破 20 日最高做多,跌破 10 日最低离场。

逻辑:海龟交易法入场系统(唐奇安 20/10 变体)的现货简化版——只保留
"长窗口入场 + 短窗口离场"的核心结构(原版含加减仓与 ATR 仓位管理,
现货单标的版本用固定量替代)。日线级别信号少、噪音低,适合长线趋势;
持仓期间回撤较大,需要较强的心理承受与风控配合。

可调常量:ENTRY_WINDOW 入场窗口,EXIT_WINDOW 离场窗口,AMOUNT 每笔下单量。
"""
ENTRY_WINDOW = 20  # 突破前 19 日最高入场
EXIT_WINDOW = 10   # 跌破前 9 日最低离场
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
        ctx.log(f"海龟入场 close={bar.close:.2f} > {n_high:.2f}")
    elif bar.close < n_low and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"海龟离场 close={bar.close:.2f} < {n_low:.2f}")
