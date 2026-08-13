# 变更日志

本项目变更记录。日期为本地时间(UTC+8)。

## 项目演进

按功能域组织,便于从功能视角回溯(git 历史已按功能单元整理,每个里程碑对应若干条完整提交):

| 时间 | 里程碑 | 内容 |
|---|---|---|
| 2026-06 | 项目骨架 | Spring Modulith 骨架 + 7 模块边界 + ArchUnit 守 domain |
| 2026-06 | 账户与鉴权 | JWT 鉴权 + 交易所账户 + API key AES-256-GCM 加密 |
| 2026-06 | 行情接入 | CCXT 行情 + ticker/kline worker + WS 推送 |
| 2026-07 | 交易与撮合 | 订单生命周期 + 撮合 + 持仓 + PaperExecutor |
| 2026-07 | 风控 | 风控闸 + RuleEvaluator 链 + RiskDecision 审计 |
| 2026-07 | 模拟盘真实化 | 模拟盘余额真实化 + 后端契约补全 + OpenAPI |
| 2026-07 | 报表 | 报表模块(回测 / 持仓 / 成交) |
| 2026-07 | 策略 worker | Python 策略 worker + 回测 runner + Docker |
| 2026-07 | 前端契约与 UI | 前端契约链 + 前端首批页面 |
| 2026-07 | MCP server | MCP server(21 工具 + PAT + Spring AI MCP) |
| 2026-07 | PERP 前端 | PERP 合约前端(下单 / 持仓 / 强平 UI) |
| 2026-07 | 实盘接入 | 实盘 CCXT(LiveExecutor + testnet 验证) |
| 2026-07 | 风控强化 | 风控闭环(MaxInitialMargin + spike 验证) |
| 2026-08 | 实盘 PERP 结算同步 | 实盘 PERP 强平同步 + 资金费率落账 |
| 2026-08 | 全仓与回测合约 | CROSS 全仓 + PAPER 资金费率 + 回测 PERP |
| 2026-08 | 稳定性与开源化 | 实盘对账/凭证/PnL 口径集中修复 + 开源文档 |

commit 细节:`git log --oneline`。历史按功能单元整理,每条提交对应一个完整功能/修复单元,同文件反复迭代的 churn 已折叠。

## 0.2.0 — 2026-08-08

### CLI

- **分域重构**:按行情 / 订单 / 组合 / 策略 / 风控 5 域拆分源文件(`market.ts` / `orders.ts` / `portfolio.ts` / `strategy.ts` / `risk.ts` + `shared.ts` 公共辅助),对标长桥 `longbridge-terminal` 命令分组
- **查询命令全覆盖**(对标长桥 130+):
  - 行情:`quote`(多 symbol)、`kline`(多周期 1m/5m/15m/1h/4h/1d)、`depth`(盘口)、`pairs`(交易对)、`tickers`(批量排序分页)
  - 组合:`portfolio pnl`、`portfolio equity-curve`、`history`、`history stats`
  - 订单:`orders`(分页 + status/时间过滤)、`order get`、`fills`(成交明细)
  - 策略:`strategies`、`strategy get`、`backtests`、`backtest`
  - 风控:`risk policies`、`risk decisions`
- **写命令 + confirm 闸**:
  - `order submit`(PAPER 免确认 / LIVE 须 `--confirm`)
  - `order cancel`(DELETE,免确认)
  - `position close`(PAPER 免 / LIVE 须 `--confirm`)
  - `strategy start / restart`(一律 `--confirm`,可能启动实盘)、`stop / pause`(免)
- **多 symbol quote**:对标长桥 `longbridge quote TSLA.US NVDA.US`,一次查多个
- `apiDelete` 加入 client;`requireAccount` fallback 第一个账户;`confirmWrite` 按账户 PAPER/LIVE 分流

### 前端

- **LandingPage 重做**:像素级复刻长桥 `open.longbridge.com/zh-CN` 9 区块结构(Nav / Hero+统计 / CLI 终端演示 / AI Skill 客户端墙+对话 / 托管 MCP 接入验证 / REST+WS 直连 / 能力目录数字+bullet / Get started 01-03 / Footer 四分栏),全程 DESIGN.md token 零硬编码,客户端 logo 用单字母方块仿长桥避免第三方商标

### 文档

- 新增 `docs/cli-reference.md`(CLI 命令详参考)
- 新增 `docs/mcp-setup.md`(MCP 接入各客户端配置)
- 新增 `docs/llm-integration.md`(LLM/AI 四接入选型 + LLMs Text 标准)
- 新增 `docs/changelog.md`(本文件)
- 新增 `docs/llms.txt`(机读文档索引)
- 重写 `skills/install.md` 复刻长桥安装流程(CLI/MCP 二选一 + Skill 安装 + 各客户端配置位置 + 故障排查)
- 更新 `cli/README.md` 命令表对标长桥 + 写操作 confirm 闸说明
- 更新 `skills/README.md` CLI 命令段

## 0.1.0 — 2026-08-08

### CLI(初始)

- `auth login / status / logout`(JWT 凭证,~/.kwikquant/credentials.json 0600)
- `accounts list / balance`(交易所账户 / 按币种余额)
- `quote`(最新价,默认 okx spot)
- `portfolio`(组合汇总)
- `positions`(持仓,无 --account fallback 第一个账户)
- `--format json | table` + `--base-url` 全局 option(子命令级)
- JWT 鉴权(REST),PAT 仅 MCP client 用
