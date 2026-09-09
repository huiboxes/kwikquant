"""滚动 VWAP 偏离回归:价格偏离成交量加权均价过大时反向做,回归后离场。

逻辑:以最近 WINDOW 根的 VWAP(典型价按成交量加权)为公允价锚。收盘价
低于 VWAP 超过 BAND 视为超跌(买),高于 VWAP 超过 BAND 视为超涨(有仓则卖)。
成交量加权使锚点偏向真实成交密集区,比简单均线更贴近市场成本。
适合高流动性标的的震荡行情;单边行情中偏离会持续扩大,需风控兜底。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
window VWAP 窗口,band 偏离阈值(比例),amount 每笔下单量。
"""
from decimal import Decimal

WINDOW = int(PARAMS.get("window", 48))   # VWAP 滚动窗口(15m × 48 = 12 小时)
BAND = float(PARAMS.get("band", 0.008))  # 偏离 0.8% 触发(比例,非金额)
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


def _rolling_vwap(highs, lows, closes, volumes):
    pv, vol = 0.0, 0.0
    for i in range(len(closes)):
        typical = (highs[i] + lows[i] + closes[i]) / 3.0
        pv += typical * volumes[i]
        vol += volumes[i]
    return pv / vol if vol > 0 else closes[-1]


def on_bar(bar, ctx):
    highs = ctx.history("high", WINDOW)
    lows = ctx.history("low", WINDOW)
    closes = ctx.history("close", WINDOW)
    volumes = ctx.history("volume", WINDOW)
    if len(closes) < WINDOW:
        return
    vwap = _rolling_vwap(highs, lows, closes, volumes)
    deviation = (bar.close - vwap) / vwap
    pos = ctx.position(ctx.symbol)
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    if deviation < -BAND and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"低于 VWAP 做多 deviation={deviation:.2%} vwap={vwap:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif deviation > BAND and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"高于 VWAP 平仓 deviation={deviation:.2%} vwap={vwap:.2f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
