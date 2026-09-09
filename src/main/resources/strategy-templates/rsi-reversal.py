"""RSI 超卖反转:RSI 跌破超卖线做多,升破超买线平仓(均值回归)。

逻辑:RSI(相对强弱指数)衡量近 PERIOD 根涨跌动能。RSI<OVERSOLD 视为
超卖反弹机会入场;RSI>OVERBOUGHT 视为超买离场。适合区间震荡行情,
单边下跌中超卖可以更超卖(建议配合风控日亏限额)。此处用简单平均 RSI。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
period RSI 周期,oversold/overbought 阈值,amount 每笔下单量。
"""
from decimal import Decimal

PERIOD = int(PARAMS.get("period", 14))          # RSI 周期
OVERSOLD = int(PARAMS.get("oversold", 30))      # 超卖线
OVERBOUGHT = int(PARAMS.get("overbought", 70))  # 超买线
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
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    if rsi < OVERSOLD and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"RSI 超卖做多 rsi={rsi:.1f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif rsi > OVERBOUGHT and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"RSI 超买平仓 rsi={rsi:.1f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
