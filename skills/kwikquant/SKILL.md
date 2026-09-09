---
name: kwikquant
description: |
  KwikQuant 加密货币量化交易 MCP 工具集总入口。当用户需要查询加密货币行情(K线 / ticker / 盘口 / 资金费率)、
  管理交易所账户(余额 / 持仓 / 组合 / 交易历史)、下单或平仓(SPOT / PERP,含风控)、回测与对比策略、
  启动模拟盘或实盘、查看 / 设置风控规则、紧急停止策略时,使用本 skill。支持 binance / okx / bitget,
  SPOT 与永续合约 PERP(含资金费率 / 强平历史)。所有写操作经风控网关,高危写操作(实盘下单 / 启动实盘 /
  改风控规则 / 紧急停止)走 confirmToken 两阶段确认,PAT 须开通对应 scope。
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
- **PERP 特有**: 杠杆(leverage 1-100,per-symbol 以交易所声明为准)、保证金模式(marginMode: isolated / cross)、仓位方向(positionEffect: open_long / open_short / close_long / close_short;side 由其派生,矛盾组合拒单)、资金费率(按交易所资金周期结算,多数标的 8h、部分 4h/1h)、强平
- **数量单位**: 下单 amount 一律**币数量**(base coin,如 0.01 = 0.01 BTC);PERP 合约张数由后端在交易所边界按 contractSize 换算,任何入口不接触张数

## 金额约定(金额红线)

- MCP 出入参的金额/价格/数量/比率一律 **decimal string**(如 `"0.01"` `"50000.5"` `"0.6000"`),防 JSON number 经 double 丢精度
- 入参示例:submit_order 的 amount / price;输出示例:行情价格、盘口档位、余额 free / used / total、资金费率、盈亏、回测绩效指标等
- Agent 侧参与金额计算前先转高精度 decimal,禁止 float / double 直接运算

## 鉴权与所有权

- 所有工具调用经 PAT(Personal Access Token)鉴权,token 关联用户身份
- PAT 带五档 scope:READ(只读工具)/ BACKTEST(run_backtest)/ TRADE(submit_order、cancel_order、close_position、start_paper_trading)/ LIVE(start_live_trading)/ RISK(set_risk_rules、emergency_stop)。新签发默认仅 READ,写工具须在签发时显式开通对应 scope,否则 10005
- scope 管"这个工具能不能调",两阶段确认管"这一次是否确认过",两层独立防护
- 涉及 accountId 的工具会校验账户归属当前用户,越权返 1002
- apiKey 等敏感字段在工具层剥离,不暴露给 Agent

## 高危写操作:两阶段确认

覆盖实盘账户的 `submit_order` / `cancel_order` / `close_position`,以及 `set_risk_rules` / `start_live_trading` / `emergency_stop`(模拟盘写操作免确认,直接执行)。

1. **第一阶段**:不带 `confirmToken` 调用,零副作用,返 `{tool, confirmToken, expiresInSec, preview}`——preview 是本次操作的要素快照(订单字段 / 规则内容 / 将停策略清单),拿给用户看并获认可
2. **第二阶段**:**复述完全相同的参数** + `confirmToken` 再调一次才真执行

令牌一次性、默认 120s 过期、与 (用户, 工具, 参数) 指纹绑定:过期 / 已用 / 参数被改 / 跨用户一律 10006,重新走第一阶段拿新令牌即可。

## 错误码

| code | 含义 |
|---|---|
| 10001 | PAT 无效 / 过期 / 吊销 |
| 10002 | 工具参数非法(枚举值错 / 格式错) |
| 10005 | PAT scope 不足(写工具未开通对应 scope) |
| 10006 | confirmToken 无效(过期 / 已用 / 参数指纹不符) |
| 1002 | 越权(账户不属于当前用户) |
| 4001 | 资源不存在 |
| 6001 | 交易所 API 失败(限频 / 网络) |
| 200 | 风控拒绝(业务结果非错误,返 status=RISK_REJECTED) |

## 典型工作流

1. **查行情决策**:`get_ticker` → `get_funding_rate`(PERP 判断费率方向)
2. **下单**:`list_accounts` 拿 accountId → `submit_order`(SPOT 直接传;PERP 传 leverage / marginMode / positionEffect;实盘账户先拿 preview + confirmToken 再复述执行)
3. **持仓监控**:`get_positions` → `get_funding_history` / `get_liquidation_history`(PERP 复盘)
4. **策略迭代**:`run_backtest` → `compare_backtests` → `start_paper_trading` → 验证后 `start_live_trading`(两阶段确认)
5. **风控**:`get_risk_rules` → `set_risk_rules`(两阶段确认);异常时 `emergency_stop`(两阶段确认,第一阶段返将停策略清单)
