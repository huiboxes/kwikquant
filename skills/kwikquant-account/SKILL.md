---
name: kwikquant-account
description: |
  KwikQuant 账户与组合查询工具。当用户需要列出已连接的交易所账户、查询指定账户实时余额、
  查询组合汇总(多账户多币种)、或查询交易历史(含盈亏 / 手续费统计)时使用。全部只读,不写表。
  list_accounts 不返回 apiKey 明文(防泄露给 Agent)。
---

# KwikQuant 账户与组合

4 个工具,全部只读。

## 工具

### list_accounts
列出已连接的交易所账户。返 id / exchange / label / paperTrading / status(**不含 apiKey**)。无入参。

### get_balances
查指定账户实时余额。入参:accountId(须属当前用户,越权 1002)。交易所 API 失败抛 6001。

### get_portfolio
查组合汇总(多交易所账户 + 各币种余额)。无入参。无账户返空 summary。

### get_trade_history
查交易历史(含盈亏 / 手续费统计)。入参全可选:accountId(省略查全部)/ symbol / since / until(ISO-8601)/ page(从 1)/ pageSize。
返 items + stats(总成交额 / 手续费 / 已实现盈亏)。

## 典型场景

- **下单前查 accountId**:`list_accounts` → 拿到 id + paperTrading 标志
- **复盘**:`get_trade_history` 查某时段已实现盈亏
- **总览资产**:`get_portfolio` 看跨账户组合
