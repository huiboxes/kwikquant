"""肯特纳通道突破:收盘突破 EMA+K×ATR 做多,跌回 EMA 下方平仓。

逻辑:肯特纳通道 = EMA(PERIOD) ± MULT×ATR(ATR_WINDOW)。相比布林带用
标准差,ATR 度量真实波幅对跳空/长影线更稳健,通道更平滑。突破上轨视为
波动放大的趋势起点入场;价格回落至中轨(EMA)下方说明动量消退离场。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
period EMA 周期,atr_window ATR 周期,mult 通道宽度,amount 每笔下单量。
"""
from decimal import Decimal

PERIOD = int(PARAMS.get("period", 20))          # 中轨 EMA 周期
ATR_WINDOW = int(PARAMS.get("atr_window", 10))  # ATR 周期
MULT = float(PARAMS.get("mult", 2.0))           # 通道宽度倍数(指标系数,非金额)
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


def _atr(highs, lows, closes, period):
    trs = []
    for i in range(1, len(highs)):
        tr = max(
            highs[i] - lows[i],
            abs(highs[i] - closes[i - 1]),
            abs(lows[i] - closes[i - 1]),
        )
        trs.append(tr)
    return sum(trs[-period:]) / period


def on_bar(bar, ctx):
    warmup = max(PERIOD, ATR_WINDOW + 1)
    highs = ctx.history("high", warmup)
    lows = ctx.history("low", warmup)
    closes = ctx.history("close", warmup)
    if len(highs) < warmup or len(lows) < warmup:
        return
    ema = _ema_series(closes, PERIOD)
    atr = _atr(highs, lows, closes, ATR_WINDOW)
    upper = ema[-1] + MULT * atr
    pos = ctx.position(ctx.symbol)
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    if bar.close > upper and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"突破上轨做多 close={bar.close:.2f} upper={upper:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif bar.close < ema[-1] and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"跌回中轨平仓 close={bar.close:.2f} ema={ema[-1]:.2f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
