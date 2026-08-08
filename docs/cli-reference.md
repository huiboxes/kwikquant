# CLI 命令参考

`kwikquant` CLI 是 AI 原生命令行工具,直连 KwikQuant 后端 REST(`/api/v1/**`),覆盖行情 / 账户 / 组合 / 订单 / 持仓 / 策略 / 回测 / 风控。对标长桥 `longbridge-terminal`(130+ 命令),聚焦加密域(SPOT + PERP)。

## 快速开始

```bash
cd cli && pnpm install && pnpm build && npm link -g   # 安装
kwikquant auth login <username> <password>            # 登录存 JWT
kwikquant quote BTC/USDT                              # 查最新价
kwikquant portfolio                                   # 组合汇总
```

## Symbol 格式

- **canonical**:`BTC/USDT`(含 `/`,CCXT 风格)
- CLI 内部按端点自动转换:`@PathVariable` 用 `-`(BTC-USDT,Spring 不解 `%2F`),`@RequestParam` 直接传 canonical
- 用户输入统一用 `BTC/USDT`,CLI 自动处理

## 认证 / 账户

| 命令 | 参数 | 说明 |
|---|---|---|
| `auth login <user> <pass>` | `--base-url <url>`(默认 http://localhost:8080) | 登录存 JWT(~/.kwikquant/credentials.json,0600) |
| `auth status` | — | 看登录状态 + 校验文件权限 |
| `auth logout` | — | 清除本地凭证 |
| `accounts list` | 全局 option | 列交易所账户(模拟盘/实盘) |
| `accounts balance <accountId>` | 全局 option | 查账户余额(按币种:可用/冻结/总额) |

## 行情

| 命令 | 参数 | 说明 |
|---|---|---|
| `quote <symbols...>` | `-e/--exchange`(okx)、`-m/--market-type`(spot) | 查最新价(支持多 symbol) |
| `kline <symbol>` | `-p/--period`(1h)、`--limit`(100)、`--before <iso>` | 历史 K 线(1m/5m/15m/1h/4h/1d) |
| `depth <symbol>` | `-d/--depth`(20) | 盘口深度 |
| `pairs` | `-e`、`-m` | 交易对列表 |
| `tickers` | `-e`、`-m`、`--sort`(quoteVolume)、`--order`(desc)、`--limit`(200)、`--search` | 批量行情(排序分页) |

### quote 返回字段

```
交易对  最新价   买一     卖一     24h量
```
- `stale` 标记:非 persistent symbol 无 worker 持续推 → CCXT 单次快照,标注 `(stale)`

### kline 返回字段

`openTime / open / high / low / close / volume`

## 组合 / 持仓 / 交易历史

| 命令 | 参数 | 说明 |
|---|---|---|
| `portfolio` | `--mode`(PAPER/LIVE) | 组合汇总(按账户:总资产 USDT) |
| `portfolio pnl` | `--mode`(PAPER/LIVE) | 盈亏汇总(实时快照,无 days 参数) |
| `portfolio equity-curve` | `--days`(7)、`--mode` | 权益曲线 |
| `positions` | `-a/--account`、`--symbol` | 持仓列表(无 --account 用第一个账户) |
| `history` | `-a`、`--symbol`、`--start`、`--end`、`--page`、`--page-size` | 交易历史(分页) |
| `history stats` | `-a`、`--since`、`--mode` | 交易统计 |

### positions 返回字段

`accountId / symbol / side / qty / avgEntryPrice / unrealizedPnl / marginMode`(PERP 含 `leverage`)

### history stats 返回字段

`totalVolume / totalFees / realizedPnl / tradingDays / winRate`

## 订单(含写操作)

| 命令 | 参数 | 说明 |
|---|---|---|
| `orders` | `-a`(必填或 fallback)、`--symbol`、`--status`、`--start`、`--end`、`--page`、`--page-size` | 分页查询订单 |
| `order get <id>` | — | 订单详情(键值表) |
| `order submit` | `-a`(必填)、`-s/--symbol`(必填)、`--side`(buy/sell)、`--type`(market/limit)、`--amount`(必填)、`--price`(limit 必填)、`-m`、`--margin-mode`、`--leverage`、`--time-in-force`、`--stop-price`(STOP 必填)、`--expire-at`(GTD 必填)、`--client-order-id`、`--confirm` | 提交订单(写;exchange 由 accountId 推导,无 -e) |
| `order cancel <id>` | — | 撤单(DELETE,免确认) |
| `position close <id>` | `-a/--account`(必填)、`--confirm`(实盘) | 平仓(模拟盘免确认,实盘须 --confirm) |
| `fills <orderId>` | — | 成交明细 |

### order submit 写操作确认闸

- 模拟盘(PAPER):免 `--confirm`,提示后执行
- 实盘(LIVE):必须 `--confirm`,否则拒绝

### orders 返回字段

`orderId / symbol / side / orderType / amount / price / status / filledQty`(PERP 含 `leverage / marginMode`)

### fills 返回字段

`fillId / price / qty / fee / side / liquidity`

## 策略 / 回测

| 命令 | 参数 | 说明 |
|---|---|---|
| `strategies` | — | 策略列表 |
| `strategy get <id>` | — | 策略详情(键值表) |
| `strategy start <id>` | `-a/--account`(首次必填)、`--confirm`(必填) | 启动策略(高危) |
| `strategy stop <id>` | — | 停止(免确认) |
| `strategy pause <id>` | — | 暂停(免确认) |
| `strategy restart <id>` | `-a/--account`(切账户必填)、`--confirm`(必填) | 重启(高危) |
| `backtests` | `-s/--strategy-id` | 回测任务列表 |
| `backtest <id>` | — | 回测详情 |

## 风控

| 命令 | 参数 | 说明 |
|---|---|---|
| `risk policies` | `-a` | 风控规则(省略查全部账户) |
| `risk decisions` | `-a`、`--verdict`(APPROVED/REJECTED)、`--start`、`--end`、`--page`、`--page-size` | 风控决策审计(后端 list 不收 orderId) |

## 输出格式

所有命令支持 `--format json`(默认 table),可管道 jq:

```bash
kwikquant quote BTC/USDT --format json | jq '.[0].last'
kwikquant kline BTC/USDT -p 1h --limit 24 --format json | jq '.[].close'
kwikquant orders --status FILLED --format json | jq '.content[] | {symbol, filledQty}'
kwikquant history stats --format json | jq '.realizedPnl'
kwikquant tickers --sort percentage --limit 10 --format json | jq '.[] | {symbol, percentage}'
```

全局 `--base-url <url>` 覆盖 credentials 里的后端地址(多环境切换)。

## 故障排查

| 现象 | 原因 | 解法 |
|---|---|---|
| `未登录。请先 auth login` | 无 credentials | `kwikquant auth login <user> <pass>` |
| `JWT 已过期` | expiresIn 到期 | 重新 `auth login` |
| `[1001] 未认证` | JWT 无效 | 重新 `auth login` |
| `[1002] 越权` | accountId 不属于当前用户 | `accounts list` 查自己账户 |
| `[4103] 参数非法` | symbol/status 枚举非法 | symbol 用 canonical `BTC/USDT`;status 大写逗号分隔 |
| `[4105] 风控拒绝` | 超额/日亏损/频率 | 查 `risk policies`,调参后重试(200 非错误) |
| quote 全 `-` | TickerResponse 嵌套 `{ticker, stale}` | CLI 已解构,若仍空检查 exchange 大写 |
| `实盘写操作...加 --confirm` | 实盘账户缺 --confirm | 加 `--confirm`(真实成交不可逆) |
