---
name: kwikquant-risk
description: |
  KwikQuant 风控规则与紧急停止工具。当用户需要查看风控规则、设置风控规则(最大下单额 / 日亏损限额 / 下单频率)、
  或紧急停止所有运行中策略时使用。emergency_stop 是高危操作,须 confirm=true 二次确认,
  前置同步审计(EMERGENCY_STOP),审计失败则策略未停(fail-closed)。
---

# KwikQuant 风控

3 个工具。

## 规则类型

| ruleType | 含义 | params 示例 |
|---|---|---|
| MAX_NOTIONAL | 单笔最大下单额(USDT) | `{"maxNotional": "5000"}` |
| DAILY_LOSS_LIMIT | 日亏损限额(USDT) | `{"dailyLossLimit": "500"}` |
| ORDER_FREQUENCY | 下单频率(单位时间内次数) | `{"windowSeconds": "60", "maxCount": "10"}` |

## 工具

### get_risk_rules
查看风控规则。入参可选:accountId(省略查全部,非空校验归属)。返规则列表(ruleType / params / enabled)。

### set_risk_rules
设置风控规则:
- **更新**:传 policyId + name + 可选 params / enabled(ruleType 不可改)
- **新建**:传 accountId + ruleType + name + 可选 params / enabled

非法 ruleType 抛 10002。

### emergency_stop
紧急停止当前用户所有 RUNNING 策略(高危)。入参:**confirm=true**(缺抛 10004)。
前置同步审计(EMERGENCY_STOP + batchUuid),审计失败则策略未停。
返 {batchUuid, stoppedCount, strategyIds, failedStrategyIds}。无 RUNNING 策略返 stoppedCount:0(非错误)。

## 典型场景

- **调风控参数**:`get_risk_rules` → `set_risk_rules(policyId=..., params={...})`
- **异常熔断**:`emergency_stop(confirm=true)` 一键停所有策略,拿 batchUuid 复盘

## 注意

- **emergency_stop 不可逆**:停所有 RUNNING 策略,confirm=true 才执行
- **审计 fail-closed**:审计写失败时策略不会被停(宁可不停也不能无审计地停)
- **部分失败可见**:返 failedStrategyIds,运维须排查未停的策略
