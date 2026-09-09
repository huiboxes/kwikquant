"""唐奇安通道突破:收盘价突破前 N 根最高价做多,跌破前 M 根最低价平仓。

逻辑:经典 CTA 趋势跟踪(海龟交易法同源)。入场用较长窗口 N 防假突破,
离场用较短窗口 M 保利润(让利润奔跑、亏损尽快离场)。
突破策略胜率低但盈亏比高,依赖少数大趋势覆盖多次小止损。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
entry_window 入场窗口,exit_window 离场窗口,amount 每笔下单量。
"""
from decimal import Decimal

ENTRY_WINDOW = int(PARAMS.get("entry_window", 20))  # 入场:突破前 19 根最高(不含当前 bar,避免自指)
EXIT_WINDOW = int(PARAMS.get("exit_window", 10))    # 离场:跌破前 9 根最低
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
    highs = ctx.history("high", ENTRY_WINDOW)
    lows = ctx.history("low", EXIT_WINDOW)
    if len(highs) < ENTRY_WINDOW or len(lows) < EXIT_WINDOW:
        return
    n_high = max(highs[:-1])
    n_low = min(lows[:-1])
    pos = ctx.position(ctx.symbol)
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    if bar.close > n_high and pos.qty <= 0 and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"突破做多 close={bar.close:.2f} > {n_high:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif bar.close < n_low and pos.qty > 0 and tradable:
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"跌破平仓 close={bar.close:.2f} < {n_low:.2f}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
