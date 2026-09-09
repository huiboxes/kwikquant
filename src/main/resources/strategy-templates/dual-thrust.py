"""Dual Thrust 区间突破:价格冲上锚点+K1×区间幅度做多,跌破锚点-K2×幅度平仓。

逻辑:经典日内突破策略。以近 N 根的区间幅度 Range = max(HH-LC, HC-LL)
衡量波动能量,收盘价突破"前一收盘 ± K×Range"通道视为真突破。现货简化:
原版做空腿改为平仓离场。K1<K2 时入场更积极、离场更保守(追涨稳守)。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
lookback 区间窗口,k1 入场系数,k2 离场系数,amount 每笔下单量。
"""
from decimal import Decimal

LOOKBACK = int(PARAMS.get("lookback", 20))  # 区间统计窗口
K1 = float(PARAMS.get("k1", 0.5))           # 入场系数(越小越激进;指标系数,非金额)
K2 = float(PARAMS.get("k2", 0.5))           # 离场系数
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


def on_bar(bar, ctx):
    highs = ctx.history("high", LOOKBACK)
    lows = ctx.history("low", LOOKBACK)
    closes = ctx.history("close", LOOKBACK + 1)
    if len(highs) < LOOKBACK or len(closes) < LOOKBACK + 1:
        return
    hh = max(highs[:-1])   # 前 N 根最高
    ll = min(lows[:-1])    # 前 N 根最低
    hc = max(closes[:-1])  # 前 N 根收盘最高
    lc = min(closes[:-1])  # 前 N 根收盘最低
    range_width = max(hh - lc, hc - ll)
    anchor = closes[-2]    # 锚点:前一收盘
    pos = ctx.position(ctx.symbol)
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    if bar.close > anchor + K1 * range_width and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"DualThrust 突破做多 close={bar.close:.2f} range={range_width:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif bar.close < anchor - K2 * range_width and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"DualThrust 跌破平仓 close={bar.close:.2f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
