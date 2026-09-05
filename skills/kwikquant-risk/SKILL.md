---
name: kwikquant-risk
description: |
  KwikQuant 风控规则与紧急停止工具。当用户需要查看风控规则、设置风控规则(最大下单额 / 日亏损限额 / 下单频率 / PERP 保证金占用)、
  或紧急停止所有运行中策略时使用。set_risk_rules 与 emergency_stop 均走两阶段 confirmToken 确认,
  emergency_stop 前置同步审计(EMERGENCY_STOP),审计失败则策略未停(fail-closed)。
---

# KwikQuant 风控

3 个工具。

## 规则类型

| ruleType | 含义 | params 示例 |
|---|---|---|
| MAX_NOTIONAL | 单笔最大下单额(USDT,仅 SPOT;PERP 跳过,走 MAX_INITIAL_MARGIN) | `{"maxNotionalUsdt": "5000"}` |
| DAILY_LOSS_LIMIT | 日亏损限额(USDT) | `{"maxLossUsdt": "500"}` |
| ORDER_FREQUENCY | 下单频率(60s 窗口内最大单数) | `{"maxPerMinute": "10"}` |
| MAX_INITIAL_MARGIN | PERP 初始保证金占用上限(总余额 × 比例,0-1 小数) | `{"maxInitialMarginRatio": "0.8"}` |

## 工具

### get_risk_rules
查看风控规则。入参可选:accountId(省略查全部,非空校验归属)。返规则列表(ruleType / params / enabled)。

### set_risk_rules
设置风控规则(需 PAT RISK scope,缺则 10005):
- **更新**:传 policyId + name + 可选 params / enabled(ruleType 不可改)
- **新建**:传 accountId + ruleType + name + 可选 params / enabled

非法 ruleType 抛 10002。

**无条件两阶段确认**:不带 confirmToken 调用不执行,返 RiskRulesPreview + 一次性令牌(TTL 120s);
复述**完全相同**的参数并携 confirmToken 再调才落库。令牌过期/复用/参数不一致抛 10006,须重走第一阶段。

### emergency_stop
紧急停止当前用户所有 RUNNING 策略(高危,需 PAT RISK scope)。唯一入参:可选 confirmToken,同样两阶段:
- **第一阶段**(不带令牌):返将停 RUNNING 策略清单 EmergencyStopPreview + 一次性令牌(TTL 120s),不停任何策略
- **第二阶段**(携令牌复述):前置同步审计(EMERGENCY_STOP + batchUuid),审计失败则策略未停;随后逐个 stop

返 {batchUuid, stoppedCount, strategyIds, failedStrategyIds}。无 RUNNING 策略返 stoppedCount:0(非错误)。

## 典型场景

- **调风控参数**:`get_risk_rules` → `set_risk_rules(policyId=..., params={...})` 拿预览+令牌 → 复述参数+令牌再调
- **异常熔断**:`emergency_stop()` 拿将停清单+令牌 → `emergency_stop(confirmToken=...)` 执行,拿 batchUuid 复盘

## 注意

- **emergency_stop 不可逆**:停所有 RUNNING 策略,须两阶段令牌确认后才执行
- **审计 fail-closed**:审计写失败时策略不会被停(宁可不停也不能无审计地停)
- **部分失败可见**:返 failedStrategyIds,运维须排查未停的策略
