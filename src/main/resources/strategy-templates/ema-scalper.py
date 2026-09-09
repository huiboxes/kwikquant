"""短周期均线动量(1 分钟):EMA9 上穿 EMA21 做多,下穿平仓(日内高频信号)。

逻辑:1m 级别的双 EMA 交叉,捕捉日内短促动量段。信号频率高、单次盈亏小,
手续费与滑点占比大(回测已计 taker 费),适合验证执行链路或作为高频
信号骨架二次开发。趋势短暂且噪音大,实盘前务必先用回测核对费用敏感性。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
fast/slow EMA 周期,amount 每笔下单量。
"""
from decimal import Decimal

FAST = int(PARAMS.get("fast", 9))    # 快线 EMA
SLOW = int(PARAMS.get("slow", 21))   # 慢线 EMA
AMOUNT = Decimal(str(PARAMS.get("amount", "0.005")))  # 每笔下单量(BTC,小额高频)

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


def on_bar(bar, ctx):
    closes = ctx.history("close", SLOW + 1)
    if len(closes) < SLOW + 1:
        return
    fast = _ema_series(closes, FAST)
    slow = _ema_series(closes, SLOW)
    pos = ctx.position(ctx.symbol)
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    crossed_up = fast[-2] <= slow[-2] and fast[-1] > slow[-1]
    crossed_down = fast[-2] >= slow[-2] and fast[-1] < slow[-1]
    if crossed_up and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"1m 金叉做多 fast={fast[-1]:.2f} slow={slow[-1]:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif crossed_down and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"1m 死叉平仓 fast={fast[-1]:.2f} slow={slow[-1]:.2f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
