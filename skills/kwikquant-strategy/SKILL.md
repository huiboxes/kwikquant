---
name: kwikquant-strategy
description: |
  KwikQuant 策略回测与启停工具。当用户需要提交回测并等结果、列出历史回测、对比多次回测、
  启动模拟盘或实盘时使用。实盘启动走 confirmToken 两阶段确认(真实下单不可逆)。
  回测提交后默认轮询约 15s,超时返 taskId 供续查。
---

# KwikQuant 策略与回测

5 个工具。各工具须 PAT 开通对应 scope:run_backtest→BACKTEST,list_backtests / compare_backtests→READ,start_paper_trading→TRADE,start_live_trading→LIVE。新签发 PAT 默认仅 READ,scope 不足抛 10005(见总入口 [kwikquant](../kwikquant/SKILL.md))。

## 工具

### run_backtest
提交回测并等结果(双模式):
- **提交模式**:传 strategyId + symbol + timeframe + start + end + params(JSON) → 提交并轮询(默认 3s×5≈15s)
- **查询模式**:传 taskId(strategyId 留空)→ 直接查一次当前状态

返 taskId / status / result:COMPLETED(result=回测结果 JSON)/ FAILED(errorMessage)/ RUNNING(超时降级,hint 引导续查,非错误)。不含 reportId——reportId 从 list_backtests 的 items[].id 拿。

### list_backtests
列出历史回测结果(分页)。入参可选:symbol / page / pageSize。返绩效指标摘要列表(totalReturn / sharpeRatio / maxDrawdown / winRate / profitFactor,decimal string,见总入口 [kwikquant](../kwikquant/SKILL.md))。

### compare_backtests
对比多次回测。入参:reportId 列表。返排序矩阵。

### start_paper_trading
启动模拟盘。入参:strategyId + accountId(paperTrading=true)。校验 account.exchange == strategy.exchange,不匹配抛 10002。

### start_live_trading
启动实盘(真实下单高危)。入参:strategyId + accountId(paperTrading=false) + 可选 confirmToken。同样校验 exchange 匹配与 paperTrading=false,不匹配抛 10002。

**两阶段确认**:不带 confirmToken 首调零副作用,返预览(strategyId / strategyName / accountId / accountName / exchange / symbol / interval)+ 一次性 confirmToken(默认 120s 过期,绑定参数指纹);给用户看过预览认可后,**复述完全相同的参数** + confirmToken 再调才真执行。令牌过期 / 已用 / 参数被改抛 10006,重走第一阶段拿新令牌。

## 典型工作流

```
run_backtest(strategyId=5, symbol=BTC/USDT, timeframe=1h, start=..., end=..., params={...})
→ COMPLETED, result=结果 JSON

list_backtests() → 拿多个 reportId(items[].id)
compare_backtests(reportIds=[10,11,12]) → 选最优

start_paper_trading(strategyId=5, accountId=paperAccountId)
→ 模拟跑 N 天验证

start_live_trading(strategyId=5, accountId=liveAccountId)
→ 返预览 + confirmToken,拿给用户确认
start_live_trading(strategyId=5, accountId=liveAccountId, confirmToken=...)
→ 实盘上线
```

## 注意

- **先回测后实盘**:不要跳过 run_backtest 直接 start_live_trading
- **exchange 须匹配**:策略创建时绑 exchange,启动时账户 exchange 须一致
- **实盘不可逆**:start_live_trading 真实下单,confirmToken 两阶段确认后才执行
