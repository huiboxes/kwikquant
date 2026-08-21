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

## SPOT vs PERP 入参差异

| 参数 | SPOT | PERP |
|---|---|---|
| leverage | null | 必填,1-125 |
| marginMode | null | 必填,isolated / cross |
| positionEffect | null | 必填,open_long / open_short / close_long / close_short |

PERP 缺任一抛 10002。

## 工具

### submit_order
下单(经风控)。入参:accountId / marketType / symbol / side(buy / sell)/ orderType(market / limit)/ amount / price(**decimal string**,如 "0.001";limit 必填,market 传 null;金额一律字符串防浮点误差) + PERP 三参。

风控拒绝返 `status=RISK_REJECTED`(code=200,非错误,Agent 应告知用户被风控拦截而非重试)。

示例:
```
账户 1,okx,市价单买 0.001 BTC/USDT 现货
→ submit_order(accountId=1, marketType=spot, symbol=BTC/USDT, side=buy, orderType=market, amount="0.001", price=null)

账户 2,okx,10x isolated 做多 0.01 BTC/USDT 永续,限价 60000
→ submit_order(accountId=2, marketType=perp, symbol=BTC/USDT, side=buy, orderType=limit, amount="0.01", price="60000", leverage=10, marginMode=isolated, positionEffect=open_long)
```

### cancel_order
撤单。入参:orderId。返最新订单状态。

### get_positions
查账户持仓列表。入参:accountId。返各持仓合约字段(marginMode / leverage / liquidationPrice 等)+ 当前市价 + 未实现盈亏 + 累计资金费(PERP)。

### get_open_orders
查未终结挂单(NEW / PENDING_NEW / SUBMITTED / PARTIALLY_FILLED / PENDING_CANCEL)。入参:accountId。

### close_position
平仓(反向市价单)。入参:positionId(从 get_positions 取)。持多→SELL,持短→BUY。flat 抛 4001。PERP 自动派生 CLOSE_LONG / CLOSE_SHORT + 透传 leverage / marginMode。

### get_funding_history
资金费率结算历史(PERP,8h 结算一次)。入参:accountId + 可选 symbol / limit(默认 50,最大 200)。返每笔明细(费率 / 金额 / 结算时间 / 持仓量)。SPOT 返空。

### get_liquidation_history
强平历史(PERP)。入参:accountId + 可选 symbol / limit。返每笔强平明细(强平价=markPrice / 数量 / 已实现盈亏 / 时间)。无强平返空。

## 注意

- **实盘真实下单不可逆**:建议先用模拟盘账户验证策略
- **PERP 平仓用 close_position 而非 submit_order**:close_position 自动派生反向 + 透传保证金参数,手动 submit 需自己算 positionEffect
- **风控拒绝不重试**:RISK_REJECTED 是业务结果,告知用户调整风控规则(见 kwikquant-risk)而非盲目重试
