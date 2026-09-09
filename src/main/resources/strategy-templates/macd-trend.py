"""MACD 趋势跟踪:DIF 上穿信号线(DEA)做多,下穿平仓。

逻辑:MACD 由快慢 EMA 之差(DIF)与其信号线(DEA)组成。DIF 上穿 DEA
(金叉)视为动能转强入场,下穿(死叉)离场。比单一均线交叉滞后更小、
噪音更少,适合中等周期趋势行情;震荡市同样会产生交叉磨损。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
fast/slow EMA 周期,signal 信号线周期,amount 每笔下单量。
"""
from decimal import Decimal

FAST = int(PARAMS.get("fast", 12))     # 快线 EMA
SLOW = int(PARAMS.get("slow", 26))     # 慢线 EMA
SIGNAL = int(PARAMS.get("signal", 9))  # 信号线 EMA
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
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    # 金叉:上一 bar DIF<=DEA 且当前 DIF>DEA;死叉反之
    crossed_up = dif[-2] <= dea[-2] and dif[-1] > dea[-1]
    crossed_down = dif[-2] >= dea[-2] and dif[-1] < dea[-1]
    if crossed_up and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"MACD 金叉做多 dif={dif[-1]:.2f} dea={dea[-1]:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif crossed_down and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"MACD 死叉平仓 dif={dif[-1]:.2f} dea={dea[-1]:.2f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
