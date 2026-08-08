# KwikQuant CLI

命令行直连 KwikQuant 后端——行情 / 账户 / 组合 / 持仓。对标长桥 `longbridge-terminal`,聚焦加密域。

## 安装

```bash
cd cli
pnpm install
pnpm build          # 编译到 dist/
npm link -g         # 全局 kwikquant 命令(可选)
```

开发模式(不编译,直接跑 tsx):
```bash
pnpm exec tsx src/index.ts accounts list
# 注意:`pnpm dev -- ...` 会被 pnpm 拦截 --format / --base-url 全局 option;
# 开发带 option 时用 `pnpm exec tsx src/index.ts ...`,或 build 后 `node dist/index.js` / `npm link -g` 全局 `kwikquant`
```

## 首次登录

```bash
kwikquant auth login <username> <password>
# → 登录拿 JWT(accessToken)→ 存 ~/.kwikquant/credentials.json (0600)
```

CLI 直连 REST(/api/v1/**),走 JWT 鉴权(JwtAuthenticationFilter);PAT 仅 MCP client 走 /mcp/** 用,CLI 不签不发。JWT 等同账户密码,本地 `~/.kwikquant/credentials.json` 权限 0600(仅属主可读),**不要提交 git**。

## 命令

| 命令 | 说明 |
|---|---|
| `auth login <user> <pass>` | 登录 + 存 JWT |
| `auth status` | 看当前登录状态(校验文件权限) |
| `auth logout` | 清除本地凭证 |
| `accounts list` | 列交易所账户(模拟盘 / 实盘) |
| `accounts balance <id>` | 查指定账户余额(按币种:可用/冻结/总额) |
| `quote <symbol> [-e okx] [-m spot\|perp]` | 查最新价(默认 okx spot) |
| `portfolio` | 组合汇总(按账户:总资产 USDT) |
| `positions [-a <accountId>]` | 持仓列表(无 --account 自动用第一个账户) |

## 输出格式

默认 table,加 `--format json` 输出 JSON(可管道 jq / awk):

```bash
kwikquant quote BTC/USDT --format json | jq '.ticker.last'
kwikquant accounts list --format json | jq '.[] | select(.paperTrading==true)'
kwikquant positions --format json | jq '.[] | {symbol, side, qty, unrealizedPnl}'
```

全局 `--base-url <url>` 覆盖 credentials 里的后端地址。

## 示例

```bash
$ kwikquant auth login demo demo-pass
✓ 已登录 demo,JWT 已存 ~/.kwikquant/credentials.json

$ kwikquant accounts list
ID  交易所  标签        类型    状态
--  ------  ----------  ------  ----
2   OKX     默认模拟盘  模拟盘  ACTIVE
5   OKX     主账户      实盘    ACTIVE

$ kwikquant accounts balance 2
币种   可用       冻结   总额
-----  ---------  ----  ---------
USDT   4851.90    134.7  4986.62
BTC    1.0002     0      1.0002

$ kwikquant quote BTC/USDT
交易对     最新价    买一     卖一     24h 量
--------   ------   ------   ------   ------
BTC/USDT   64998.3  64998.3  64998.4  3239.45

$ kwikquant portfolio
账户ID  交易所  标签        总资产(USDT)
------  ------  ----------  ------------
5       OKX     主账户      71921.92

$ kwikquant positions --format json | jq '.[] | {symbol, side, qty, unrealizedPnl}'
```

## 安全

- JWT 存 `~/.kwikquant/credentials.json`,目录 0700 / 文件 0600
- `auth status` 校验文件权限,不达标警告
- JWT 过期自动检测(`assertAuthed` 比 `expiresAt`),过期提示重新 login
- 写操作(下单 / 平仓)不在 CLI 暴露——走 MCP Skill 或前端,高危操作需 `confirm=true` 二次确认

## 当前定位(诚实)

本地起步(`localhost:8080`),适合开发者本机用。公网分发是后续工作。
