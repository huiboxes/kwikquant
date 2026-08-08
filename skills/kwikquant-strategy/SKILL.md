---
name: kwikquant-strategy
description: |
  KwikQuant 策略回测与启停工具。当用户需要提交回测并等结果、列出历史回测、对比多次回测、
  启动模拟盘或实盘时使用。实盘启动须 confirm=true 二次确认(真实下单不可逆)。
  回测提交后默认轮询约 15s,超时返 taskId 供续查。
---

# KwikQuant 策略与回测

5 个工具。

## 工具

### run_backtest
提交回测并等结果(双模式):
- **提交模式**:传 strategyId + symbol + timeframe + start + end + params(JSON) → 提交并轮询(默认 3s×5≈15s)
- **查询模式**:传 taskId(strategyId 留空)→ 直接查一次当前状态

返 status:COMPLETED(结果 JSON)/ FAILED(errorMessage)/ RUNNING(超时降级,hint 引导续查,非错误)。

### list_backtests
列出历史回测结果(分页)。入参可选:symbol / page / pageSize。返绩效指标摘要列表。

### compare_backtests
对比多次回测。入参:reportId 列表。返排序矩阵。

### start_paper_trading
启动模拟盘。入参:strategyId + accountId(paperTrading=true)。校验 account.exchange == strategy.exchange,不匹配抛 10002。

### start_live_trading
启动实盘(真实下单高危)。入参:strategyId + accountId(paperTrading=false) + **confirm=true**(缺抛 10004)。

## 典型工作流

```
run_backtest(strategyId=5, symbol=BTC/USDT, timeframe=1h, start=..., end=..., params={...})
→ COMPLETED, 拿 reportId

list_backtests() → 拿多个 reportId
compare_backtests(reportIds=[10,11,12]) → 选最优

start_paper_trading(strategyId=5, accountId=paperAccountId)
→ 模拟跑 N 天验证

start_live_trading(strategyId=5, accountId=liveAccountId, confirm=true)
→ 实盘上线
```

## 注意

- **先回测后实盘**:不要跳过 run_backtest 直接 start_live_trading
- **exchange 须匹配**:策略创建时绑 exchange,启动时账户 exchange 须一致
- **实盘不可逆**:start_live_trading 真实下单,confirm=true 才执行
