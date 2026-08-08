---
name: kwikquant
description: |
  KwikQuant 加密货币量化交易 MCP 工具集总入口。当用户需要查询加密货币行情(K线 / ticker / 盘口 / 资金费率)、
  管理交易所账户(余额 / 持仓 / 组合 / 交易历史)、下单或平仓(SPOT / PERP,含风控)、回测与对比策略、
  启动模拟盘或实盘、查看 / 设置风控规则、紧急停止策略时,使用本 skill。支持 binance / okx / bitget,
  SPOT 与永续合约 PERP(含资金费率 / 强平历史)。所有写操作经风控网关,高危操作(实盘 / 紧急停止)需二次确认。
---

# KwikQuant MCP Skills

KwikQuant 是加密货币量化交易后端,通过 MCP server 暴露 23 个工具,按 5 个域分包:

| Skill | 域 | 工具数 | 何时用 |
|---|---|---|---|
| kwikquant-market | 行情数据 | 4 | 查 K线 / 最新价 / 盘口 / 资金费率 |
| kwikquant-account | 账户与组合 | 4 | 查账户 / 余额 / 组合 / 交易历史 |
| kwikquant-trading | 下单与持仓 | 7 | 下单 / 撤单 / 持仓 / 平仓 / 资金费历史 / 强平历史 |
| kwikquant-strategy | 策略与回测 | 5 | 回测 / 对比 / 启动模拟盘或实盘 |
| kwikquant-risk | 风控 | 3 | 查 / 设风控规则 / 紧急停止 |

## 前置:连接 MCP server

首次使用须先安装连接,见 [install.md](../install.md)。连接后所有工具通过 MCP 协议动态发现,无需手动配置。

## 交易所与市场

- **交易所**: binance / okx / bitget
- **市场类型**: spot(现货) / perp(永续合约)
- **交易对格式**: CCXT 风格 `BTC/USDT` `ETH/USDT`(含 `/`)
- **PERP 特有**: 杠杆(leverage 1-125)、保证金模式(marginMode: isolated / cross)、仓位方向(positionEffect: open_long / open_short / close_long / close_short)、资金费率(8h 结算)、强平

## 鉴权与所有权

- 所有工具调用经 PAT(Personal Access Token)鉴权,token 关联用户身份
- 涉及 accountId 的工具会校验账户归属当前用户,越权返 1002
- apiKey 等敏感字段在工具层剥离,不暴露给 Agent

## 错误码

| code | 含义 |
|---|---|
| 10001 | PAT 无效 / 过期 / 吊销 |
| 10002 | 工具参数非法(枚举值错 / 格式错) |
| 10004 | 高危操作缺 confirm=true |
| 1002 | 越权(账户不属于当前用户) |
| 4001 | 资源不存在 |
| 6001 | 交易所 API 失败(限频 / 网络) |
| 200 | 风控拒绝(业务结果非错误,返 status=RISK_REJECTED) |

## 典型工作流

1. **查行情决策**:`get_ticker` → `get_funding_rate`(PERP 判断费率方向)
2. **下单**:`list_accounts` 拿 accountId → `submit_order`(SPOT 直接传;PERP 传 leverage / marginMode / positionEffect)
3. **持仓监控**:`get_positions` → `get_funding_history` / `get_liquidation_history`(PERP 复盘)
4. **策略迭代**:`run_backtest` → `compare_backtests` → `start_paper_trading` → 验证后 `start_live_trading`(须 confirm=true)
5. **风控**:`get_risk_rules` → `set_risk_rules`;异常时 `emergency_stop`(须 confirm=true)
