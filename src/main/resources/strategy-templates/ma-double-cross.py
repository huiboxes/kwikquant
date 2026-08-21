"""均线双金叉:MA5 上穿 MA10 且 MA10>MA20(双金叉)做多;死叉平仓。

逻辑:单一金叉噪音大,要求快线穿上 + 中线在慢线上方(双重确认)才入场,
MA5 跌破 MA10 离场。适合单边趋势行情,震荡市会因频繁交叉产生磨损成本。

可调常量:FAST/MID/SLOW 均线周期,AMOUNT 每笔下单量。
"""
FAST = 5     # 快线周期
MID = 10     # 中线周期
SLOW = 20    # 慢线周期
AMOUNT = 0.01  # 每笔下单量(BTC)


def _ma(values, n):
    return sum(values[-n:]) / n


def on_bar(bar, ctx):
    closes = ctx.history("close", SLOW)
    if len(closes) < SLOW:
        return
    ma_fast = _ma(closes, FAST)
    ma_mid = _ma(closes, MID)
    ma_slow = _ma(closes, SLOW)
    pos = ctx.position(ctx.symbol)
    # 双金叉:快线在中线上方(已穿上)且中线在慢线上方(趋势向上)
    if ma_fast > ma_mid and ma_mid > ma_slow and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"双金叉做多 fast={ma_fast:.2f} mid={ma_mid:.2f} slow={ma_slow:.2f}")
    elif ma_fast < ma_mid and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"死叉平仓 fast={ma_fast:.2f} mid={ma_mid:.2f}")
