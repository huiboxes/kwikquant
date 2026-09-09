"""均线双金叉:MA5 上穿 MA10 且 MA10>MA20(双金叉)做多;死叉平仓。

逻辑:单一金叉噪音大,要求快线穿上 + 中线在慢线上方(双重确认)才入场,
MA5 跌破 MA10 离场。适合单边趋势行情,震荡市会因频繁交叉产生磨损成本。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
fast/mid/slow 均线周期,amount 每笔下单量。
"""
from decimal import Decimal

FAST = int(PARAMS.get("fast", 5))    # 快线周期
MID = int(PARAMS.get("mid", 10))     # 中线周期
SLOW = int(PARAMS.get("slow", 20))   # 慢线周期
AMOUNT = Decimal(str(PARAMS.get("amount", "0.01")))  # 每笔下单量(BTC)

# 重复下单防护:runner 模式成交异步,ctx.position() 下单后不会立即刷新,
# 信号持续时会重复下单。下单受理后登记下单时持仓基线,持仓离开基线
# (成交确认;方向无关,PERP signed 做空/强平同样离开基线)前不再下单;
# 等待满 PENDING_TIMEOUT 根仍未决则放行重试(订单被拒/被撤的恢复通道)。
# 回测成交在次 bar 撮合快照价(FAST 市价=last±滑点,last 取该 bar 收盘),
# 意向次 bar 即决,成交路径与无防护一致;拒单路径重试节奏变为每
# PENDING_TIMEOUT 根一次(拒单原因随价格变化时成交时点随之移动,warnings 漂移)。
PENDING_TIMEOUT = 3   # 调大→拒单后冻结更久;调小→成交慢于超时时双单概率上升
_PENDING_BASE = None  # 未决意向=下单时持仓基线;None 表示无未决意向
_PENDING_BARS = 0     # 意向已等待的 bar 数


def _guard_allows(pos):
    """推进未决意向状态机,返回本 bar 是否允许下单(每 bar 恰好调用一次,无信号也推进)。"""
    global _PENDING_BASE, _PENDING_BARS
    if _PENDING_BASE is None:
        return True
    _PENDING_BARS += 1
    if pos.qty != _PENDING_BASE or _PENDING_BARS >= PENDING_TIMEOUT:
        _PENDING_BASE, _PENDING_BARS = None, 0
        return True
    return False


def _mark_pending(pos):
    """下单受理后登记未决意向(基线=下单时持仓,成交确认=持仓离开基线)。"""
    global _PENDING_BASE, _PENDING_BARS
    _PENDING_BASE, _PENDING_BARS = pos.qty, 0


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
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    # 双金叉:快线在中线上方(已穿上)且中线在慢线上方(趋势向上)
    if ma_fast > ma_mid and ma_mid > ma_slow and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"双金叉做多 fast={ma_fast:.2f} mid={ma_mid:.2f} slow={ma_slow:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif ma_fast < ma_mid and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"死叉平仓 fast={ma_fast:.2f} mid={ma_mid:.2f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
