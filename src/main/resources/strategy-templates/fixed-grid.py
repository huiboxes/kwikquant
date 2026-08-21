"""固定网格:围绕 N 根均线基线,每跌一格买、每涨一格卖,低买高卖赚震荡。

逻辑:以 BASE_WINDOW 根收盘均价为基线,价格每下穿 GRID_STEP 一档买一格,
每上穿一档卖一格。震荡市持续收割波段差价;单边下跌会累积持仓(用
MAX_POS 限格数防单边爆仓),单边上涨会卖飞(仅卖出持仓,不做空)。

可调常量:BASE_WINDOW 基线窗口,GRID_STEP 格距(比例),AMOUNT 每格量,MAX_POS 仓位上限。
"""
BASE_WINDOW = 50   # 基线:最近 50 根收盘均价
GRID_STEP = 0.01   # 每格 1%
AMOUNT = 0.01      # 每格下单量(BTC)
MAX_POS = 0.05     # 最多持 5 格,防单边下跌累积


def _level(price, baseline):
    # 当前价位相对基线在第几格(四舍五入到整格)
    return round((price - baseline) / (baseline * GRID_STEP))


def on_bar(bar, ctx):
    closes = ctx.history("close", BASE_WINDOW)
    if len(closes) < BASE_WINDOW:
        return
    baseline = sum(closes) / len(closes)
    prev_close = closes[-2]
    level = _level(bar.close, baseline)
    prev_level = _level(prev_close, baseline)
    pos = ctx.position(ctx.symbol)
    # 下穿一格 → 买一格(低位吸筹);上穿一格 → 卖一格(高位派发)
    if level < prev_level and pos.qty < MAX_POS:
        ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        ctx.log(f"网格买入 level={level} baseline={baseline:.2f}")
    elif level > prev_level and pos.qty > 0:
        sell_amount = AMOUNT if pos.qty >= AMOUNT else pos.qty
        ctx.place_order(side="SELL", order_type="MARKET", amount=sell_amount)
        ctx.log(f"网格卖出 level={level} pos={pos.qty}")
