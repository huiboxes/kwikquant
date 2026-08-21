"""变动率动量:N 根涨幅超过阈值做多,涨幅归零离场(强者恒强)。

逻辑:ROC(变动率)= 当前收盘相对 N 根前的涨跌幅。涨幅超过 THRESHOLD
视为动量启动,顺势做多;ROC 跌破 0(价格低于 N 根前)说明动量耗尽离场。
动量策略与趋势策略同源但信号更直接,适合波动活跃的主流标的;横盘期
阈值附近反复进出会产生磨损。

可调常量:PERIOD 回看周期,THRESHOLD 入场涨幅阈值(比例),AMOUNT 每笔下单量。
"""
PERIOD = 24       # 回看周期(4h × 24 = 4 天)
THRESHOLD = 0.02  # 涨幅超 2% 入场
AMOUNT = 0.01     # 每笔下单量(BTC)


def on_bar(bar, ctx):
    closes = ctx.history("close", PERIOD + 1)
    if len(closes) < PERIOD + 1:
        return
    base = closes[0]
    roc = (bar.close - base) / base
    pos = ctx.position(ctx.symbol)
    if roc > THRESHOLD and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"动量入场 roc={roc:.2%}")
    elif roc < 0 and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"动量离场 roc={roc:.2%}")
