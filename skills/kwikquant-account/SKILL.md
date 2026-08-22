---
name: kwikquant-account
description: |
  KwikQuant 账户与组合查询工具。当用户需要列出已连接的交易所账户、查询指定账户实时余额、
  查询组合汇总(多账户多币种)、或查询交易历史(含盈亏 / 手续费统计)时使用。全部只读,不写表。
  list_accounts 不返回 apiKey 明文(防泄露给 Agent)。
---

# KwikQuant 账户与组合

4 个工具,全部只读,不写表。`apiKey` 明文在工具层剥离(防泄露给 Agent)。

## 工具

### list_accounts
列出已连接的交易所账户。返 `id / exchange / label / paperTrading / status`(**不含 apiKey**)。无入参。

自然语言示例:
```
列出我的交易所账户
```
CLI 对应:`kwikquant accounts list`

### get_balances
查指定账户实时余额(按币种:可用 / 冻结 / 总额)。入参:`accountId`(须属当前用户,越权 1002)。交易所 API 失败抛 6001。

```
账户 2 的余额
```
CLI 对应:`kwikquant accounts balance 2`

### get_portfolio
查组合汇总(多交易所账户 + 各币种余额,折算 USDT 总资产)。无入参。无账户返空 summary。

```
我的组合汇总
```
CLI 对应:`kwikquant portfolio`(`--mode PAPER|LIVE` 过滤)

### get_trade_history
查交易历史(含盈亏 / 手续费统计)。入参全可选:`accountId`(省略查全部)/ `symbol` / `since` / `until`(ISO-8601)/ `page`(从 1)/ `pageSize`。
返 `items` + `stats`(总成交额 / 手续费 / 已实现盈亏 / 交易天数 / 胜率)。

```
账户 2 这个月的交易历史,带统计
```
CLI 对应:`kwikquant history -a 2` + `kwikquant history stats -a 2`

## 返回字段

详细字段见 [REST API 参考](../../docs/api-reference.md)(accounts / portfolio / trade-history 段)。关键字段:

| 工具 | 关键返回字段 |
|---|---|
| list_accounts | id, exchange, label, paperTrading, status(无 apiKey) |
| get_balances | 币种 → {free, used, total} |
| get_portfolio | accounts[], accountId, exchange, paperTrading, label, totalUsdt, balances[]{currency, free, used, total, usdtValue} |
| get_trade_history | items[](amount, filledQty, filledAvgPrice, totalFee, totalVolume), stats{totalVolume, totalFees, realizedPnl, tradingDays, winRate} |

金额红线:free / used / total / usdtValue / totalUsdt 及 trade history 的金额与盈亏字段一律 decimal string(见总入口 [kwikquant](../kwikquant/SKILL.md))。

## 错误码

| code | 含义 |
|---|---|
| 1002 | accountId 不属于当前用户(越权) |
| 4001 | 账户不存在 |
| 6001 | 交易所 API 失败(限频 / 网络 / 代理) |

## 典型场景

- **下单前查 accountId**:`list_accounts` → 拿到 id + paperTrading 标志(模拟盘 / 实盘)
- **复盘**:`get_trade_history` 查某时段已实现盈亏
- **总览资产**:`get_portfolio` 看跨账户组合(折算 USDT)
