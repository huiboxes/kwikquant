# KwikQuant MCP Skills

KwikQuant 是加密货币量化交易后端,通过 MCP(Model Context Protocol)server 暴露 **23 个工具**,按 5 个域分包为 Anthropic Agent Skills,可被 Claude Code / Cursor / Codex 等任意支持 MCP 的 AI 客户端安装使用。

对标长桥(Longbridge)Skill 的云原生 MCP 架构,但聚焦加密货币域——支持 SPOT 现货与永续合约 PERP(含杠杆 / 保证金模式 / 资金费率 8h 结算 / 强平历史),这是股票券商 MCP 没有的能力。

## Skill 目录

| Skill | 域 | 工具数 | 何时用 |
|---|---|---|---|
| [kwikquant](kwikquant/SKILL.md) | 总入口 | — | 路由到各域 |
| [kwikquant-market](kwikquant-market/SKILL.md) | 行情数据 | 4 | K线 / 最新价 / 盘口 / 资金费率 |
| [kwikquant-account](kwikquant-account/SKILL.md) | 账户与组合 | 4 | 账户 / 余额 / 组合 / 交易历史 |
| [kwikquant-trading](kwikquant-trading/SKILL.md) | 下单与持仓 | 7 | 下单 / 撤单 / 持仓 / 平仓 / 资金费历史 / 强平历史 |
| [kwikquant-strategy](kwikquant-strategy/SKILL.md) | 策略与回测 | 5 | 回测 / 对比 / 启动模拟盘或实盘 |
| [kwikquant-risk](kwikquant-risk/SKILL.md) | 风控 | 3 | 查 / 设风控规则 / 紧急停止 |

## 快速安装

见 [install.md](install.md)。核心三步:
1. 启动 KwikQuant 后端(`./mvnw spring-boot:run`,MCP server 暴露在 `http://localhost:8080/mcp`)
2. 登录前端,在 Settings → MCP Tokens 生成 PAT(明文仅显示一次)
3. 在 Claude Code / Cursor 配置 MCP server,把 PAT 填入 `Authorization: Bearer <PAT>`

## CLI 命令行

除 MCP / Skill 外,还提供 `kwikquant` CLI(`cli/` 目录,Node + TypeScript),直连后端 REST:

```bash
cd cli && pnpm install && pnpm build
kwikquant auth login <user> <pass>     # 登录存 JWT(~/.kwikquant/credentials.json,0600)
kwikquant accounts list                # 列账户(模拟盘 / 实盘)
kwikquant quote BTC/USDT               # 查最新价(默认 okx spot)
kwikquant portfolio                    # 组合汇总
kwikquant positions [--account <id>]   # 持仓(无 --account 自动用第一个账户)
kwikquant accounts list --format json  # JSON 输出,可管道 jq / awk
```

CLI 走 JWT 鉴权(REST 端点),PAT 仅 MCP client 用。CLI 也含写命令(`order submit`/`order cancel`、`position close`、`strategy start`/`restart`),按账户 PAPER/LIVE 分流确认(模拟盘免确认,实盘须 `--confirm`)。详见 [cli/README.md](../cli/README.md)。

## 技术特性

- **协议**: MCP Streamable HTTP(`POST /mcp`),Spring AI 2.0 server
- **鉴权**: PAT(Personal Access Token),HMAC 哈希 + pepper fail-closed,`Authorization: Bearer` 传递
- **所有权校验**: 涉及 accountId 的工具均校验归属当前用户,越权 1002
- **风控**: 所有下单经 RiskGate,风控拒绝返 `status=RISK_REJECTED`(200,非错误)
- **高危二次确认**: `start_live_trading` / `emergency_stop` 须 `confirm=true`,缺抛 10004
- **敏感字段隔离**: `apiKey` 等在 MCP 工具层剥离,不暴露给 Agent

## 支持的交易所与市场

- **交易所**: binance / okx / bitget
- **市场**: spot(现货) / perp(永续合约)
- **交易对**: CCXT 风格 `BTC/USDT`(含 `/`)

## 与长桥 Skill 的差异

KwikQuant 是加密货币域,长桥是股票券商域。不复刻长桥的多市场覆盖(港 / 美 / A 股)、社区 API、财报 / 基本面 / 机构研究(股票特有);复刻的是**架构层**:远程 MCP + Skill 分包 + PAT 鉴权 + 多客户端可装 + 高危二次确认。差异化优势在 **PERP 保证金 / 资金费率 / 强平**——股票券商 MCP 没有这些。

## 更多文档

- [快速上手](../docs/quickstart.md) — 10 分钟跑通(后端 → 注册 → 模拟盘下单 → 接 AI)
- [Cookbook 任务式指南](../docs/cookbook.md) — 按「我想做 X」组织(含 PERP / 回测 / 用 AI 下单 / 风控)
- [CLI 命令参考](../docs/cli-reference.md) — 全命令参数 + 返回字段 + 故障排查
- [REST API 参考](../docs/api-reference.md) — 63 端点全表(OpenAPI 生成,防漂移)
- [MCP 接入](../docs/mcp-setup.md) — 各客户端配置 + PAT 签发 + 故障排查
- [llms-full.txt](../docs/llms-full.txt) — 全量单页 AI 上下文(含本目录 + 7 接入文档)

## 当前定位(诚实)

本项目目前是**本地起步**(`localhost:8080`),适合开发者本机用 Claude Code / Cursor 接入验证。要开源分发或让外部用户安装,需先做公网部署(内网穿透或云主机 + 域名 + HTTPS),这是后续工作。
