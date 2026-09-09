---
name: kwikquant-trading
description: |
  KwikQuant 下单与持仓管理工具。当用户需要在加密货币交易所下单(现货或永续合约)、撤单、
  查询持仓、查询未终结挂单、平仓、查资金费率结算历史、查强平历史时使用。
  所有下单经风控网关,风控拒绝返 status=RISK_REJECTED(非错误)。支持 SPOT 与 PERP
  (PERP 须传杠杆 / 保证金模式 / 仓位方向)。
---

# KwikQuant 下单与持仓

7 个工具。所有写操作经 RiskGate 风控;涉及 accountId 的工具校验账户归属当前用户(越权 1002)。

## 实盘写操作两阶段确认

submit_order / cancel_order / close_position 对**实盘账户**强制两阶段确认(模拟盘免确认直接执行):

1. 不带 `confirmToken` 调用 → 返 `{tool, confirmToken, expiresInSec, preview}`(零副作用,**非错误也未执行**)。preview 是订单 / 撤单 / 平仓要素快照,拿给用户确认
2. 复述**完全相同参数** + `confirmToken` 再调一次才真执行

令牌一次性,默认 120s 过期;过期 / 已用 / 参数不符抛 10006,重新走第一阶段拿新预览与令牌。写工具另需 PAT 开通 TRADE scope(缺则 10005)。

## SPOT vs PERP 入参差异

| 参数 | SPOT | PERP |
|---|---|---|
| side | 必填,buy / sell | 可省略(由 positionEffect 派生:open_long/close_short→buy,open_short/close_long→sell);显式传入必须与派生值一致,矛盾抛 4103(ORDER_INVALID_PARAMS) |
| leverage | null | 必填,1-100(per-symbol 上限以交易所声明为准) |
| marginMode | null | 必填,isolated / cross |
| positionEffect | null | 必填,open_long / open_short / close_long / close_short |

PERP 缺 leverage/marginMode/positionEffect 任一抛 10002。amount 单位=币数量(base coin,如 "0.01" = 0.01 BTC),合约张数由后端边界换算。

## 工具

### submit_order
下单(经风控)。入参:accountId / marketType / symbol / side(buy / sell;PERP 可省略,由 positionEffect 派生)/ orderType(market / limit)/ amount(币数量,base coin)/ price(**decimal string**,如 "0.001";limit 必填,market 传 null;金额一律字符串防浮点误差) + PERP 三参 + clientOrderId(可选,幂等键,重试**必须复用同值**防重复下单,建议 "<意图摘要>-<随机>") + confirmToken(可选,实盘第二阶段传第一阶段返回的令牌)。

风控拒绝返 `status=RISK_REJECTED`(code=200,非错误,Agent 应告知用户被风控拦截而非重试)。

示例:
```
账户 1,okx,市价单买 0.001 BTC/USDT 现货
→ submit_order(accountId=1, marketType=spot, symbol=BTC/USDT, side=buy, orderType=market, amount="0.001", price=null)

账户 2,okx,10x isolated 做多 0.01 BTC/USDT 永续,限价 60000
→ submit_order(accountId=2, marketType=perp, symbol=BTC/USDT, side=buy, orderType=limit, amount="0.01", price="60000", leverage=10, marginMode=isolated, positionEffect=open_long)

账户 3(实盘),okx,市价单买 0.001 BTC/USDT 现货
→ 第一次 submit_order(accountId=3, ..., amount="0.001", price=null, clientOrderId="buy-btc-001-x7k2")  // 不带 confirmToken
← {tool, confirmToken, expiresInSec, preview}  // 零副作用,拿 preview 给用户确认
→ 第二次 submit_order(完全相同参数 + confirmToken="<第一阶段返回>")  // 才真下单
```

### cancel_order
撤单。入参:orderId + confirmToken(可选,实盘第二阶段传)。返最新订单状态。

### get_positions
查账户持仓列表。入参:accountId。返各持仓合约字段(marginMode / leverage / liquidationPrice 等)+ 当前市价 + 未实现盈亏 + 累计资金费(PERP)。

### get_open_orders
查未终结挂单(NEW / PENDING_NEW / SUBMITTED / PARTIALLY_FILLED / PENDING_CANCEL)。入参:accountId。

### close_position
平仓(反向市价单)。入参:positionId(从 get_positions 取)+ confirmToken(可选,实盘第二阶段传)。持多→SELL,持短→BUY。flat 抛 4001。PERP 自动派生 CLOSE_LONG / CLOSE_SHORT + 透传 leverage / marginMode。

### get_funding_history
资金费率结算历史(PERP,按交易所资金周期逐期结算,多数标的 8h、部分 4h/1h)。入参:accountId + 可选 symbol / limit(默认 50,最大 200)。返每笔明细(费率 / 金额 / 结算时间 / 持仓量)。SPOT 返空。

### get_liquidation_history
强平历史(PERP)。入参:accountId + 可选 symbol / limit。返每笔强平明细(强平价=markPrice / 数量 / 已实现盈亏 / 时间)。无强平返空。

## 注意

- **金额红线**:amount / price 入参与订单 / 持仓 / 资金费 / 强平输出的金额数量字段一律 decimal string(见总入口 [kwikquant](../kwikquant/SKILL.md))
- **实盘真实下单不可逆**:三层防护 = TRADE scope(缺则 10005)→ 两阶段 confirmToken(令牌问题 10006)→ clientOrderId 幂等;第一阶段预览响应既非失败也非已执行,不得当成结果上报。建议先用模拟盘账户验证策略(免确认直接执行)
- **PERP 平仓用 close_position 而非 submit_order**:close_position 自动派生反向 + 透传保证金参数,手动 submit 需自己算 positionEffect
- **风控拒绝不重试**:RISK_REJECTED 是业务结果,告知用户调整风控规则(见 kwikquant-risk)而非盲目重试
