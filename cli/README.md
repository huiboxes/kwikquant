# KwikQuant CLI

命令行直连 KwikQuant 后端——行情 / 账户 / 组合 / 订单 / 持仓 / 策略 / 回测 / 风控。对标长桥 `longbridge-terminal`(130+ 命令),聚焦加密域(SPOT 现货 + PERP 永续)。

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

CLI 直连 REST(`/api/v1/**`),走 JWT 鉴权(JwtAuthenticationFilter);PAT 仅 MCP client 走 `/mcp/**` 用,CLI 不签不发。JWT 等同账户密码,本地 `~/.kwikquant/credentials.json` 权限 0600(仅属主可读),**不要提交 git**。

## 命令

### 认证 / 账户

| 命令 | 说明 |
|---|---|
| `auth login <user> <pass>` | 登录 + 存 JWT |
| `auth status` | 看当前登录状态(校验文件权限) |
| `auth logout` | 清除本地凭证 |
| `accounts list` | 列交易所账户(模拟盘 / 实盘) |
| `accounts balance <id>` | 查指定账户余额(按币种:可用/冻结/总额) |

### 行情

| 命令 | 说明 |
|---|---|
| `quote <symbol...> [-e okx] [-m spot\|perp]` | 查最新价(支持多 symbol,默认 okx spot) |
| `kline <symbol> [-p 1h] [--limit 100] [--before <iso>]` | 查历史 K 线(周期 1m/5m/15m/1h/4h/1d) |
| `depth <symbol> [-d 20]` | 查盘口深度(默认 20 档) |
| `pairs [-e okx] [-m spot]` | 查交易对列表 |
| `tickers [-e okx] [--sort quoteVolume] [--limit 200] [--search BTC]` | 批量查行情(可排序分页) |

### 组合 / 持仓 / 交易历史

| 命令 | 说明 |
|---|---|
| `portfolio [--mode PAPER\|LIVE]` | 组合汇总(按账户:总资产 USDT) |
| `portfolio pnl [--mode PAPER\|LIVE]` | 盈亏汇总(实时快照) |
| `portfolio equity-curve [--days 7]` | 权益曲线 |
| `positions [-a <id>] [--symbol <sym>]` | 持仓列表(无 --account 自动用第一个账户) |
| `history [-a <id>] [--symbol] [--start] [--end] [--page] [--page-size]` | 交易历史(分页) |
| `history stats [-a <id>] [--since] [--mode]` | 交易统计(成交额/手续费/盈亏/胜率) |

### 订单(含写操作)

| 命令 | 说明 |
|---|---|
| `orders [-a <id>] [--symbol] [--status] [--start] [--end] [--page] [--page-size]` | 分页查询订单 |
| `order get <id>` | 查订单详情 |
| `order submit -a <id> -s <sym> --side buy\|sell --type market\|limit --amount <n> [--price <p>] [-m spot\|perp] [--margin-mode isolated\|cross] [--leverage <n>] [--time-in-force GTC] [--stop-price <p>] [--expire-at <iso>] [--client-order-id <id>] [--confirm]` | 提交订单(模拟盘免确认,实盘须 --confirm;exchange 由 accountId 推导) |
| `order cancel <id>` | 撤单(取消未成交单,免确认) |
| `fills <orderId>` | 查订单成交明细 |

### 持仓写操作

| 命令 | 说明 |
|---|---|
| `position close <id> -a <accountId> [--confirm]` | 平仓(模拟盘免确认,实盘须 --confirm) |

### 策略 / 回测

| 命令 | 说明 |
|---|---|
| `strategies` | 策略列表 |
| `strategy get <id>` | 查策略详情 |
| `strategy start <id> [-a <accountId>] --confirm` | 启动策略(高危,须 --confirm,首次启动必传 -a) |
| `strategy stop <id>` | 停止策略(免确认) |
| `strategy pause <id>` | 暂停策略(免确认) |
| `strategy restart <id> [-a <accountId>] --confirm` | 重启策略(高危,须 --confirm) |
| `backtests [-s <strategyId>]` | 回测任务列表(可按策略过滤) |
| `backtest <id>` | 查回测任务详情 |

### 风控

| 命令 | 说明 |
|---|---|
| `risk policies [-a <id>]` | 查风控规则(省略查全部账户) |
| `risk decisions [-a <id>] [--verdict APPROVED\|REJECTED] [--start <iso>] [--end <iso>]` | 查风控决策审计 |

## 输出格式

默认 table,加 `--format json` 输出 JSON(可管道 jq / awk):

```bash
kwikquant quote BTC/USDT --format json | jq '.[0].last'
kwikquant kline BTC/USDT -p 1h --limit 24 --format json | jq '.[].close'
kwikquant orders --status FILLED --format json | jq '.content[] | {symbol, side, filledQty}'
kwikquant history stats --format json | jq '.realizedPnl'
kwikquant tickers --sort percentage --limit 10 --format json | jq '.[] | {symbol, percentage}'
```

全局 `--base-url <url>` 覆盖 credentials 里的后端地址。

## 写操作确认闸

写操作(下单 / 平仓 / 策略启动)按账户类型分流:

- **模拟盘(PAPER)**:成交可逆,免 `--confirm`,提示后直接执行
- **实盘(LIVE)**:真实成交不可逆,**必须 `--confirm`**,否则拒绝执行

```bash
# 模拟盘下单(账户 2 是模拟盘,免 --confirm)
kwikquant order submit -a 2 -s BTC/USDT --side buy --type market --amount 0.001
✓ 模拟盘 下单(可逆,免 --confirm)

# 实盘下单(账户 5 是实盘,须 --confirm)
kwikquant order submit -a 5 -s BTC/USDT --side buy --type market --amount 0.001 --confirm

# 实盘无 --confirm → 拒绝
kwikquant order submit -a 5 -s BTC/USDT --side buy --type market --amount 0.001
下单 是实盘写操作,真实成交不可逆。加 --confirm 确认执行。
```

策略 `start` / `restart` 一律须 `--confirm`(可能启动实盘交易);`stop` / `pause` 免(停止是安全的)。撤单(`order cancel`)免确认(取消未成交单,不产生成交)。

## 示例

```bash
$ kwikquant auth login demo demo-pass
✓ 已登录 demo,JWT 已存 ~/.kwikquant/credentials.json

$ kwikquant accounts list
ID  交易所  标签        类型    状态
--  ------  ----------  ------  ----
2   OKX     默认模拟盘  模拟盘  ACTIVE
5   OKX     主账户      实盘    ACTIVE

$ kwikquant quote BTC/USDT ETH/USDT
交易对     最新价    买一     卖一     24h量
--------   ------   ------   ------   ------
BTC/USDT   64998.3  64998.3  64998.4  3239.45
ETH/USDT   3128.5   3128.4   3128.5   18422.7

$ kwikquant kline BTC/USDT -p 1h --limit 3
时间                      开       高       低       收       量
-----------------------   ------   ------   ------   ------   ------
2026-08-07T10:00:00Z      64800.0  65100.0  64750.0  65020.0  128.5
...

$ kwikquant portfolio
账户ID  交易所  标签        总资产(USDT)
------  ------  ----------  ------------
5       OKX     主账户      71921.92

$ kwikquant positions
账户  交易对     方向  数量  开仓价    未实现盈亏  保证金    杠杆
----  --------   ----  ----  --------  ----------  --------  ----
5     BTC/USDT  LONG  0.5   64250.0   +374.15     ISOLATED  10

$ kwikquant history stats --mode PAPER
指标          值
------------  --------
成交额        12450.00
累计手续费    3.12
已实现盈亏    +185.40
交易天数      7
胜率          0.65
```

## 安全

- JWT 存 `~/.kwikquant/credentials.json`,目录 0700 / 文件 0600
- `auth status` 校验文件权限,不达标警告
- JWT 过期自动检测(`assertAuthed` 比 `expiresAt`),过期提示重新 login
- 写操作 PAPER/LIVE 分流确认(见上),实盘不可逆操作须显式 `--confirm`
- 策略 `start` / `restart` 一律 `--confirm`(可能启动实盘交易)
- password 走命令行 argv(`ps` 可见),本地 dev 用;生产环境用 MCP PAT 鉴权(不走 CLI `auth login`)

## 更多文档

- [快速上手](../docs/quickstart.md) — 10 分钟跑通(装 CLI → 登录 → 下单 → 接 AI)
- [Cookbook 任务式指南](../docs/cookbook.md) — 按「我想做 X」组织(查行情 / PERP / 跑回测 / 用 AI)
- [CLI 命令参考](../docs/cli-reference.md) — 本文的详尽版(参数 + 返回字段 + 故障排查)
- [REST API 参考](../docs/api-reference.md) — 63 端点全表(CLI 直连的 REST)
- [MCP 接入](../docs/mcp-setup.md) — 不想装 CLI 时,用 MCP 一行接入
- [llms-full.txt](../docs/llms-full.txt) — 全量单页 AI 上下文

## 当前定位(诚实)

本地起步(`localhost:8080`),适合开发者本机用。公网分发(部署 + 域名 + HTTPS + ZIP + GitHub Release)是后续工作。
