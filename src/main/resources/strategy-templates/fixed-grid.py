"""固定网格:围绕 N 根均线基线,每跌一格买、每涨一格卖,低买高卖赚震荡。

逻辑:以 BASE_WINDOW 根收盘均价为基线,价格每下穿 GRID_STEP 一档买一格,
每上穿一档卖一格。震荡市持续收割波段差价;单边下跌会累积持仓(用
MAX_POS 限格数防单边爆仓),单边上涨会卖飞(仅卖出持仓,不做空)。

可调参数(任务 parameters 经 PARAMS 读取,缺省如下;金额参数建议 JSON 字符串):
base_window 基线窗口,grid_step 格距(比例),amount 每格量,max_pos 仓位上限。
"""
from decimal import Decimal

BASE_WINDOW = int(PARAMS.get("base_window", 50))    # 基线:最近 50 根收盘均价
GRID_STEP = float(PARAMS.get("grid_step", 0.01))    # 每格 1%(价格比例计算,非下单金额)
AMOUNT = Decimal(str(PARAMS.get("amount", "0.01")))  # 每格下单量(BTC)
MAX_POS = Decimal(str(PARAMS.get("max_pos", "0.05")))  # 最多持 5 格,防单边下跌累积

# 重复下单防护:runner 模式成交异步,ctx.position() 下单后不会立即刷新,
# 连续穿格会重复下单。下单受理后登记下单时持仓基线,持仓离开基线
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
    tradable = _guard_allows(pos)  # 推进未决意向状态机(runner 异步成交防重复下单)
    # 下穿一格 → 买一格(低位吸筹);上穿一格 → 卖一格(高位派发)
    if level < prev_level and pos.qty < MAX_POS and tradable:
        ack = ctx.place_order(side="BUY", order_type="MARKET", amount=AMOUNT)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"网格买入 level={level} baseline={baseline:.2f}")
        else:
            ctx.log(f"BUY 未受理: {ack.reason}")
    elif level > prev_level and pos.qty > 0 and tradable:
        sell_amount = AMOUNT if pos.qty >= AMOUNT else pos.qty
        ack = ctx.place_order(side="SELL", order_type="MARKET", amount=sell_amount)
        if ack.accepted:
            _mark_pending(pos)
            ctx.log(f"网格卖出 level={level} pos={pos.qty}")
        else:
            ctx.log(f"SELL 未受理: {ack.reason}")
