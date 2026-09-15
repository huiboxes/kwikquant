"""RunnerContext — 实盘/模拟盘 Runner 的策略 ctx。

与 BacktestContext 对偶且契约同构(``context.StrategyContext`` Protocol,docs/strategy-api.md):
place_order 走 ``trade.submit``(POST /api/v1/orders,worker token 推导 account,不传
exchange_account_id);cancel 走 ``trade.cancel``(DELETE /api/v1/orders/{id});
position/equity/available_cash 走 REST(/api/v1/positions + /api/v1/accounts/worker/balance,
worker token 推导);history 切片内存 ``_bars``(由 RunnerEventLoop 收 bar 关闭后 set_bar 填;
策略声明 ``WARMUP_BARS`` 时 worker_server 启动先经 REST 回填历史,否则初始空 warmup)。

**place_order 返回 OrderAck(不是 Fill)**:``accepted=True`` = 平台已受理(HTTP 成功,
风控/冻结已过);``filled_qty``/``filled_price`` 是**提交时点**值——PAPER 撮合由行情推送
异步驱动(提交时通常 "0")、LIVE 交易所异步回报(null)。**不要以 filled_qty 判成交**:
成交结果查 ``position()`` 或 /topic/fills 推送。失败(网络/业务拒单)返
``OrderAck(accepted=False, reason=...)``,不中断 runner(记 stderr)。

PERP 契约与回测同构:``position_effect`` 必填四向、``side`` 禁传(服务端由 effect 派生,
单一真相源);``leverage``/``margin_mode`` 未显式传时缺省用 V44 策略级绑定
(bootstrap 下发,不再把杠杆烘焙进源码)。金额红线:amount/price 拒 float
(``context.normalize_order``);REST 金额字段为 decimal string,直接 ``Decimal(str)`` 读,
不再经 float 中转。
"""

from __future__ import annotations

import sys
from decimal import Decimal, InvalidOperation
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Mapping

from kwikquant.errors import KqApiError
from kwikquant_worker.context import OrderAck, normalize_order
from kwikquant_worker.strategy import Bar, Position

if TYPE_CHECKING:
    from kwikquant.client import Client
    from kwikquant_worker.health_signals import HealthSignals


def _dec(v: Any) -> Decimal | None:
    """REST decimal string → Decimal(None/非法 → None)。字段经 @JsonFormat(STRING) 序列化,
    不经 float 中转;对旧形态 JSON number 宽容(str 往返)。"""
    if v is None:
        return None
    if isinstance(v, Decimal):
        return v
    try:
        return Decimal(str(v))
    except (InvalidOperation, ValueError):
        return None


def _dec_or_zero(v: Any) -> Decimal:
    d = _dec(v)
    return d if d is not None else Decimal(0)




def _bucket_side(row: dict) -> str | None:
    """桶方向派生:positionSide(四向桶身份,大写)优先,legacy net 模式行 fallback side(小写
    long/short)。两者都给不出 LONG/SHORT → None(脏行,调用方跳过并告警,不猜方向——
    与 TradingService.closePosition 的 legacy side 兼容口径一致)。"""
    v = str(row.get("positionSide") or "").upper()
    if v in ("LONG", "SHORT"):
        return v
    v = str(row.get("side") or "").upper()
    if v in ("LONG", "SHORT"):
        return v
    return None

class RunnerContext:
    """Runner ctx:策略 on_bar 内读 history + 下单 + 查持仓/权益(实盘/模拟盘)。"""

    def __init__(
        self,
        client: "Client",
        strategy_id: int,
        *,
        exchange: str,
        market_type: str,
        symbol: str,
        health_signals: "HealthSignals | None" = None,
        params: Mapping[str, Any] | None = None,
        leverage: int | None = None,
        margin_mode: str | None = None,
    ) -> None:
        self._client = client
        self._strategy_id = strategy_id
        self._exchange = exchange
        self._market_type = market_type
        self._symbol = symbol
        self._bars: list[Bar] = []
        self._index: int = -1
        self._signals = health_signals
        # 浅冻结:与 module.PARAMS 同一语义(不可增删键)
        self._params: Mapping[str, Any] = MappingProxyType(dict(params or {}))
        # V44 策略级绑定(bootstrap 下发):PERP 订单未显式传 leverage/margin_mode 时的缺省值
        self._leverage = leverage
        self._margin_mode = margin_mode

    # ---------- 引擎侧装配(非策略 API) ----------

    def set_bar(self, bar: Bar) -> None:
        """RunnerEventLoop bar 关闭后调:append + 推进 index(history 切片含当前 bar)。"""
        self._bars.append(bar)
        self._index = len(self._bars) - 1

    def prefill_bars(self, bars: list[Bar]) -> None:
        """WS 连接前预填历史 bar(消除 runner 重启"失忆"):一次性灌入已关闭的历史 bar。

        与 ``set_bar``(逐根 append)不同:预填直接替换 ``_bars`` + ``_index``,**不动**
        ``_current_bar``(由 WS ``_on_kline`` 首根缓存)。调用方(``worker_server._prefill_history``)
        须排除末根可能未关闭的 bar——否则 WS 推同 openTime 首根缓存→关闭后 ``set_bar`` 再 append 会重复。
        空 list → ``_index=-1``(history 返 [],等同无预填,WS 路径照常)。
        """
        self._bars = list(bars)
        self._index = len(self._bars) - 1

    # ---------- 策略 API(context.StrategyContext 契约) ----------

    @property
    def params(self) -> Mapping[str, Any]:
        return self._params

    @property
    def symbol(self) -> str:
        return self._symbol

    def _resolve_symbol(self, symbol: str | None) -> str:
        """单标的 ctx 的 symbol 归一:缺省用绑定 symbol;显式传入必须一致(fail-closed 防误用)。"""
        if symbol is None:
            return self._symbol
        if self._symbol and symbol != self._symbol:
            raise ValueError(f"runner ctx 绑定 {self._symbol!r},不接受其他 symbol: {symbol!r}")
        return symbol

    def history(self, field: str, n: int, symbol: str | None = None) -> list[float]:
        """最近 n 根(含当前)K 线的 field 值。不足 n(开头 warmup)返已有;index 未 set 返 []。"""
        self._resolve_symbol(symbol)
        if self._index < 0 or not self._bars:
            return []
        start = max(0, self._index - n + 1)
        return [getattr(b, field) for b in self._bars[start : self._index + 1]]

    def place_order(
        self,
        *,
        symbol: str | None = None,
        side: str | None = None,
        order_type: str,
        amount: Decimal | int | str,
        price: Decimal | int | str | None = None,
        position_effect: str | None = None,
        leverage: int | None = None,
        margin_mode: str | None = None,
    ) -> OrderAck:
        """实盘/模拟下单。调 trade.submit(POST /api/v1/orders,worker token 推导 account)。

        校验与回测共享单源 ``context.normalize_order``(amount/price 拒 float;SPOT side
        必填且合约字段禁传;PERP effect 必填四向、side 禁传)。PERP 的 leverage/margin_mode
        未显式传 → 用 V44 策略级绑定(bootstrap);仍为 None 则透传由服务端接受性校验裁决。

        返 ``OrderAck``:受理成功 accepted=True(filled_* 是提交时点值,成交异步);
        网络失败/业务拒单 accepted=False + reason(不抛,不中断 runner,记 stderr)。
        """
        self._resolve_symbol(symbol)
        o = normalize_order(
            market_type=self._market_type,
            side=side,
            order_type=order_type,
            amount=amount,
            price=price,
            position_effect=position_effect,
            leverage=leverage,
            margin_mode=margin_mode,
        )
        eff_lev, eff_mm = o.leverage, o.margin_mode
        if self._market_type == "PERP":
            if eff_lev is None:
                eff_lev = self._leverage
            if eff_mm is None:
                eff_mm = self._margin_mode
        try:
            resp = self._client.trade.submit(
                symbol=self._symbol,
                # PERP 不发 side:服务端由 positionEffect 派生(单一真相源,双源矛盾在入口已拒)
                side=None if self._market_type == "PERP" else o.side,
                order_type=o.order_type,
                amount=o.amount,
                price=o.price,
                market_type=self._market_type,
                leverage=eff_lev,
                margin_mode=eff_mm,
                position_effect=o.position_effect,
            )
        except KqApiError as e:
            self._record_order_outcome(False)
            print(f"[runner] place_order rejected: {e!r}", file=sys.stderr)
            reason = f"{e.code}: {e.message}" if e.code is not None else str(e.message)
            return OrderAck(accepted=False, reason=reason)
        except Exception as e:  # noqa: BLE001 — 下单失败不中断 runner
            self._record_order_outcome(False)
            print(f"[runner] place_order failed: {e!r}", file=sys.stderr)
            return OrderAck(accepted=False, reason=repr(e))
        if not isinstance(resp, dict) or resp.get("orderId") is None:
            self._record_order_outcome(False)
            return OrderAck(accepted=False, reason="malformed order submit response (missing orderId)")
        self._record_order_outcome(True)
        # 提交时点成交信息:PAPER 恒 "0"(撮合异步),LIVE null;decimal string 直读不绕 float
        return OrderAck(
            accepted=True,
            filled_qty=_dec(resp.get("filledQty")),
            filled_price=_dec(resp.get("filledAvgPrice")),
        )

    def close_position(self, symbol: str | None = None) -> OrderAck:
        """市价全平当前持仓(REST 持仓原值下单,decimal string 直读零残差)。

        无持仓(或查询失败退化为无持仓)返 ``OrderAck(accepted=False, reason="NO_POSITION")``。
        PERP 逐桶行下 CLOSE_*(每行携带自身 leverage/margin_mode,服务端按
        account+symbol+positionSide+marginMode+leverage 精确定位;缺失回退策略级绑定),
        对冲态拆多笔全平;任一桶被拒 → 聚合 OrderAck(accepted=False,reason 拼接)。
        多笔全成时返回首笔 ack(filled_* 为提交时点值,成交异步)。
        """
        sym = self._resolve_symbol(symbol)
        if self._market_type == "PERP":
            # 分桶账本:逐桶行原值下 CLOSE_*(每行携带自身 leverage/margin_mode,服务端精确定位
            # 仓位行)。对冲态(LONG+SHORT 并存)拆多笔全部平掉——与回测 close_position"平到净零"
            # 语义对齐;按净持仓下一笔会平错桶(净零时对冲仓双双裸奔)。
            rows = self._position_rows(sym)
            if not rows:
                return OrderAck(accepted=False, reason="NO_POSITION")
            acks: list[OrderAck] = []
            for it in rows:
                side_up = _bucket_side(it)
                if side_up is None:
                    print(
                        f"[runner] close_position skipped row with unrecognized direction:"
                        f" positionSide={it.get('positionSide')!r} side={it.get('side')!r}",
                        file=sys.stderr,
                    )
                    continue
                acks.append(
                    self.place_order(
                        order_type="MARKET",
                        amount=_dec_or_zero(it.get("qty")),
                        position_effect="CLOSE_LONG" if side_up == "LONG" else "CLOSE_SHORT",
                        leverage=it.get("leverage") if it.get("leverage") is not None else self._leverage,
                        margin_mode=it.get("marginMode") or self._margin_mode,
                    )
                )
            if not acks:
                return OrderAck(accepted=False, reason="NO_POSITION")
            rejected = [a for a in acks if not a.accepted]
            if rejected:
                return OrderAck(accepted=False, reason="; ".join(str(a.reason) for a in rejected))
            return acks[0]
        rows = self._position_rows(sym)
        if not rows:
            return OrderAck(accepted=False, reason="NO_POSITION")
        return self.place_order(side="SELL", order_type="MARKET", amount=_dec_or_zero(rows[0].get("qty")))

    def cancel(self, order_id: int) -> None:
        """实盘撤单(DELETE /api/v1/orders/{id},worker token 推导 account)。

        失败(网络/订单已成交 422/已终结)吞掉记 stderr,不中断 runner —— 被动限价策略
        每根 bar 撤旧挂新,撤一个已成交/已过期单是正常竞态,不是错误。
        """
        try:
            self._client.trade.cancel(int(order_id))
        except Exception as e:  # noqa: BLE001 — 撤单失败不中断 runner
            print(f"[runner] cancel failed order={order_id}: {e!r}", file=sys.stderr)

    def _record_order_outcome(self, ok: bool) -> None:
        """下单结果上报 HealthSignals(成功重置连续失败为 0,失败累加)。None 时 no-op。"""
        if self._signals is not None:
            self._signals.record_order_outcome(ok=ok)

    def _position_rows(self, sym: str) -> list[dict]:
        """REST /positions 的本标的敞口行(qty>0)。失败返 [](caller 按无持仓处理)。

        行过滤:PERP ctx 只认 marginMode 非空的桶行、SPOT ctx 只认 marginMode 空的行——
        服务端返回含 SPOT+PERP 全部持仓,first-row-wins 会把 SPOT 行当 PERP 仓读(排序上
        SPOT 行 margin_mode NULL 恒排最前)。flat 桶行(qty=0,全平后保留桶身份)一并滤除。
        """
        try:
            items = self._client.trade.positions(symbol=sym)
        except Exception as e:  # noqa: BLE001
            print(f"[runner] position query failed: {e!r}", file=sys.stderr)
            return []
        rows = []
        for it in items or []:
            if not isinstance(it, dict) or it.get("symbol") != sym:
                continue
            is_perp_row = bool(it.get("marginMode"))
            if is_perp_row != (self._market_type == "PERP"):
                continue
            if _dec_or_zero(it.get("qty")) <= 0:
                continue
            rows.append(it)
        return rows

    def position(self, symbol: str | None = None) -> Position:
        """查持仓(REST /positions,worker token 推导 account)。失败/无持仓返空 Position(qty=0)。

        PERP:paper/实盘账本是**双向分桶**模型(同 symbol 可同时有 LONG/SHORT 及不同
        leverage/marginMode 桶行),本方法聚合成 signed **净持仓**(ΣLONG − ΣSHORT,与回测
        净持仓契约同构,策略代码两侧同一写法)。方向字段(avg_price/leverage/margin_mode/
        liquidation_price)取净方向主导桶:avg_price 按 qty 加权,leverage/margin_mode/
        liquidation_price 取主导桶首行(桶间不同杠杆时的近似,docs/strategy-api.md 声明);
        unrealized_pnl 为全部桶行之和。对冲净零(LONG=SHORT)时 qty=0、方向字段 None。
        金额字段 decimal string 直读。
        """
        sym = self._resolve_symbol(symbol)
        rows = self._position_rows(sym)
        if not rows:
            return Position(symbol=sym, qty=Decimal(0), avg_price=Decimal(0))
        if self._market_type != "PERP":
            it = rows[0]
            return Position(
                symbol=sym,
                qty=_dec_or_zero(it.get("qty")),
                avg_price=_dec_or_zero(it.get("avgEntryPrice")),
                liquidation_price=_dec(it.get("liquidationPrice")),
                unrealized_pnl=_dec(it.get("unrealizedPnl")),
            )
        long_qty = Decimal(0)
        short_qty = Decimal(0)
        upl = Decimal(0)
        for it in rows:
            bucket = _bucket_side(it)
            if bucket is None:
                # 脏行(positionSide/side 均非 LONG/SHORT):跳过不猜方向——计入 LONG 会虚增净多头,
                # 与 close_position 的跳过口径一致(否则"看得见平不掉")
                print(
                    f"[runner] position row skipped, unrecognized direction: positionSide="
                    f"{it.get('positionSide')!r} side={it.get('side')!r}",
                    file=sys.stderr,
                )
                continue
            q = _dec_or_zero(it.get("qty"))
            if bucket == "SHORT":
                short_qty += q
            else:
                long_qty += q
            upl += _dec_or_zero(it.get("unrealizedPnl"))
        net = long_qty - short_qty
        dominant = "LONG" if net > 0 else "SHORT" if net < 0 else None
        dom_rows = [it for it in rows if _bucket_side(it) == dominant] if dominant else []
        avg = Decimal(0)
        if dom_rows:
            dom_qty = sum((_dec_or_zero(it.get("qty")) for it in dom_rows), Decimal(0))
            if dom_qty > 0:
                avg = sum(
                    (_dec_or_zero(it.get("avgEntryPrice")) * _dec_or_zero(it.get("qty")) for it in dom_rows),
                    Decimal(0),
                ) / dom_qty
        first = dom_rows[0] if dom_rows else None
        return Position(
            symbol=sym,
            qty=net,
            avg_price=avg,
            leverage=first.get("leverage") if first else None,
            margin_mode=(first.get("marginMode") or None) if first else None,
            liquidation_price=_dec(first.get("liquidationPrice")) if first else None,
            unrealized_pnl=upl,
        )

    # ---------- 权益(经 worker 余额通道,与回测 equity()/available_cash() 同构) ----------

    def _quote_base(self) -> tuple[str, str]:
        """symbol 派生 (base, quote):SPOT "BTC/USDT" → (BTC, USDT);
        PERP "BTC/USDT:USDT" → 结算币 (BTC, USDT)。"""
        s = self._symbol
        settle = None
        if ":" in s:
            s, settle = s.split(":", 1)
        base, _, quote = s.partition("/")
        return base, (settle or quote)

    def _balances(self) -> dict | None:
        """GET /accounts/worker/balance(RUNNER 通道,账户由 token 绑定推导)。
        失败返 None(caller 降级 Decimal(0) + stderr,不中断 runner)。"""
        try:
            resp = self._client.account.worker_balance(market_type=self._market_type)
        except Exception as e:  # noqa: BLE001 — 余额查询失败不中断 runner
            print(f"[runner] balance query failed: {e!r}", file=sys.stderr)
            return None
        currencies = resp.get("currencies") if isinstance(resp, dict) else None
        return currencies if isinstance(currencies, dict) else None

    def available_cash(self) -> Decimal:
        """可用现金/保证金(quote 计)= 绑定账户 quote 币 free(与回测账本 available 同语义:
        PERP 的 free 已扣除锁定保证金)。查询失败返 Decimal(0)(偏安全:策略按 0 仓位预算不下单)。
        PAPER 穿蚀仓强平等极端场景 free 可为负(缺口由 free 吸收,契约已声明),本方法如实
        返回负值——按预算下单的策略天然止步,不做静默 clamp。"""
        currencies = self._balances()
        if currencies is None:
            return Decimal(0)
        _, quote = self._quote_base()
        cb = currencies.get(quote)
        if not isinstance(cb, dict):
            return Decimal(0)
        return _dec_or_zero(cb.get("free"))

    def equity(self) -> Decimal:
        """账户权益(quote 计)。查询失败返 Decimal(0)。

        - SPOT:quote total + base total × 最新已收盘 close(估值用自身 bar 序列,无额外 REST;
          warmup 前无 bar 时只计 quote total)。
        - PERP:quote total(交易所口径含锁定保证金)+ 本标的持仓未实现盈亏
          (与回测 PerpLedger.equity = cash + unrealized 同构)。
        """
        currencies = self._balances()
        if currencies is None:
            return Decimal(0)
        base, quote = self._quote_base()
        q = currencies.get(quote)
        total = _dec_or_zero(q.get("total")) if isinstance(q, dict) else Decimal(0)
        if self._market_type == "PERP":
            # 全部 PERP 桶行 unrealizedPnl 求和(与 position() 同一行过滤口径)
            for it in self._position_rows(self._symbol):
                total += _dec_or_zero(it.get("unrealizedPnl"))
            return total
        b = currencies.get(base)
        base_total = _dec_or_zero(b.get("total")) if isinstance(b, dict) else Decimal(0)
        if base_total != 0 and self._bars:
            # mark-to-market 用最新已收盘 close(行情 float → str 最短往返,估值量非成交金额)
            total += base_total * Decimal(str(self._bars[-1].close))
        return total

    def log(self, msg: str) -> None:
        print(f"[strategy] {msg}", file=sys.stderr)

    def report_progress(self, processed: int, total: int) -> None:
        """runner 无 task 进度概念,无 op(进度上报仅回测 task 有)。"""
        return None

    def predicted_funding_rate(self, symbol: str | None = None) -> Decimal | None:
        """当期预估资金费率(GET /api/v1/market/funding-rate,worker token 通道;仅 PERP)。

        返回交易所**当期预估值**(带符号,正=多头付空头收),非已结算值——lookahead 契约见
        docs/strategy-api.md §9(回测/实盘已知差异,不等价)。SPOT / 交易所无预估 / 网络失败
        返 ``None``(与 position() 查询失败同纪律,绝不造值);runner 记 stderr 不中断。
        """
        self._resolve_symbol(symbol)
        if self._market_type != "PERP":
            return None  # SPOT 无资金费概念,不打请求
        try:
            view = self._client.data.funding_rate(
                exchange=self._exchange, market_type=self._market_type, symbol=self._symbol
            )
        except Exception as e:  # noqa: BLE001 — 预估费查询失败不中断 runner
            print(f"[runner] predicted funding query failed: {e!r}", file=sys.stderr)
            return None
        if not isinstance(view, dict):
            return None
        return _dec(view.get("fundingRate"))
