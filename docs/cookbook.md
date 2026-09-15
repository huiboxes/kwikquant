# Cookbook 任务式指南

> 按「我想做 X」组织的实战 walkthrough,每篇含可复制命令 + 期望输出 + 注意。新人先读 [quickstart](quickstart.md) 跑通环境,再来这里挑任务。
> CLI 走 JWT(`/api/v1/**`),MCP 走 PAT(`/mcp/**`)——两种鉴权见 [llm-integration](llm-integration.md)。本文档命令默认用模拟盘账户(`paperTrading=true`),实盘 CLI 须 `--confirm`、MCP 走两阶段 `confirmToken`。

## 目录

- [查行情](#查行情)
- [下单 SPOT 现货](#下单-spot-现货)
- [PERP 永续合约](#perp-永续合约)
- [持仓与平仓](#持仓与平仓)
- [组合与历史](#组合与历史)
- [用 AI 下单](#用-ai-下单)
- [跑回测](#跑回测)
- [启动 / 停策略](#启动--停策略)
- [风控规则与紧急停止](#风控规则与紧急停止)

## 查行情

CLI(多 symbol 批量、排序分页):

```bash
kwikquant quote BTC/USDT ETH/USDT               # 多 symbol 一次查
kwikquant kline BTC/USDT -p 1h --limit 100     # 1h K 线 100 根
kwikquant depth BTC/USDT -d 20                  # 盘口 20 档
kwikquant tickers --sort percentage --limit 10  # 涨跌幅榜 top10
kwikquant pairs -m spot                         # 现货交易对列表
```

MCP(自然语言,工具自动发现):

```
查 okx 现货 BTC/USDT 最新价和资金费率,我该不该持有多仓?
```
→ 触发 `get_ticker` + `get_funding_rate`(PERP)。正费率多头付费、空头收;负费率反之。

**注意**:
- 非持续订阅 symbol 走单次快照,CLI 标 `(stale)`
- 行情空 / 404 → OKX/Binance 需代理:yaml 配 `kwikquant.proxy.defaults.rest-proxy` / `ws-proxy`(不配即直连),或换 Bitget(直连可达)

## 下单 SPOT 现货

```bash
# 模拟盘市价买(免 confirm)
kwikquant order submit -a 2 -s BTC/USDT --side buy --type market --amount 0.001
# ✓ 订单已提交 orderId=42 status=FILLED

# 限价单 + 有效期
kwikquant order submit -a 2 -s BTC/USDT --side buy --type limit \
  --amount 0.001 --price 60000 --time-in-force GTC

# 撤单(免 confirm,取消未成交单)
kwikquant order cancel 42

# 查成交明细
kwikquant fills 42
# 成交ID  价格    数量    手续费  方向  流动性
# 1      64998.3 0.001  0.0013 BUY   taker

# 分页查历史订单
kwikquant orders -a 2 --status FILLED --page 1 --page-size 20
```

MCP:
```
在账户 2 上,市价买 0.001 BTC/USDT 现货
```
→ `submit_order(accountId=2, marketType=spot, symbol=BTC/USDT, side=buy, orderType=market, amount="0.001", price=null)`

**注意**:
- 模拟盘成交可逆(可平仓重来);实盘 CLI 须 `--confirm`、MCP 走两阶段 `confirmToken`,真实成交不可逆
- 风控拒绝返 `status=RISK_REJECTED`(code=200,非错误)——查 `risk policies` 调参,不要重试
- `limit` 单必填 `--price`;`STOP` 类必填 `--stop-price`;`GTD` 必填 `--expire-at`

## PERP 永续合约

```bash
# 10x isolated 做多 0.01 BTC 永续(市价)。--amount 单位=币数量(base coin):
# 0.01 即 0.01 BTC——交易所合约张数由后端在边界按 contractSize 换算,CLI/API/MCP 一律不接触张数
kwikquant order submit -a 2 -s BTC/USDT --side buy --type market --amount 0.01 \
  -m perp --margin-mode isolated --leverage 10

# cross 全仓 + 限价做空
kwikquant order submit -a 2 -s BTC/USDT --side sell --type limit --amount 0.01 \
  -m perp --margin-mode cross --leverage 20 --price 70000

# 持仓含 liquidationPrice / leverage / marginMode / 累计资金费
kwikquant positions -a 2 --symbol BTC/USDT
```

MCP 须显式传 `positionEffect`(CLI 自动派生,MCP 必填;`side` 对 PERP 可省略——由 positionEffect 派生,显式传入必须四象限一致):

```
账户 2,okx,10x isolated 做多 0.01 BTC/USDT 永续,市价单
```
→ `submit_order(accountId=2, marketType=perp, symbol=BTC/USDT, side=buy, orderType=market, amount="0.01", price=null, leverage=10, marginMode=isolated, positionEffect=open_long)`

**PERP 三参**(缺一抛 10002):

| 参数 | 取值 | 说明 |
|---|---|---|
| `leverage` | 1-100 | 杠杆倍数(per-symbol 上限以交易所声明为准,超限拒单) |
| `marginMode` | `isolated` / `cross` | 逐仓 / 全仓 |
| `positionEffect` | `open_long` / `open_short` / `close_long` / `close_short` | 开多 / 开空 / 平多 / 平空 |

**资金费率**:按交易所周期结算(OKX 主流合约 0/8/16 UTC,部分标的 4h/1h 网格;平台按数据网格逐期结算,不假设固定周期),正费率多头付空头、负费率反之。查历史走 MCP `get_funding_history`(CLI 暂无对应命令)。

**强平**:标记价格侵蚀保证金余额至维持保证金要求时触发:ISOLATED 按单仓保证金算(资金费侵蚀仓位保证金),CROSS 按账户级保证金余额算。持仓上的 `liquidationPrice` 是同一口径反推的参考价(随资金费结算动态重算),查历史走 MCP `get_liquidation_history`。

## 持仓与平仓

```bash
kwikquant positions -a 2                       # 全持仓
kwikquant positions -a 2 --symbol BTC/USDT     # 按 symbol 过滤
# 账户  交易对    方向  数量  开仓价   未实现盈亏  保证金    杠杆
# 2     BTC/USDT LONG  0.01  64250.0  +37.4       ISOLATED  10

# 平仓(反向市价单,模拟盘免 confirm,实盘 --confirm)
kwikquant position close <positionId> -a 2
```

MCP:
```
平掉我账户 2 的 BTC 多仓
```
→ `close_position(positionId=...)`,持多→SELL,持空→BUY,自动派生 `CLOSE_LONG` / `CLOSE_SHORT` + 透传保证金参数。flat 抛 4001。

## 组合与历史

```bash
kwikquant portfolio                       # 多账户总资产(USDT)
kwikquant portfolio pnl                   # 未实现盈亏(实时快照,无 days)
kwikquant portfolio equity-curve --days 30  # 30 天权益曲线
kwikquant history -a 2 --start 2026-08-01T00:00:00Z --end 2026-08-07T23:59:59Z
kwikquant history stats -a 2               # 成交额 / 手续费 / 已实现盈亏 / 胜率
# 指标          值
# 成交额        12450.00
# 累计手续费    3.12
# 已实现盈亏    +185.40
# 交易天数      7
# 胜率          0.65
```

MCP:
```
回顾这个月我的组合:盈亏趋势、最大赢家、最大拖累
```
→ `get_portfolio` + `get_trade_history` + stats

## 用 AI 下单

接 Claude Code(见 [quickstart 接 AI 一节](quickstart.md#8-接-ai-claude-code)签 PAT):

```bash
claude mcp add --transport http kwikquant http://localhost:8080/mcp \
  --header "Authorization: Bearer kq_pat_..."
```

对话(实盘高危写走两阶段:第一次调用只返预览 + `confirmToken`,零副作用;你认可后 AI 复述相同参数带令牌再调一次才执行):

```
列出我的交易所账户                          → list_accounts(无 apiKey 明文)
查 okx 永续 BTC/USDT 最新价 + 资金费率       → get_ticker + get_funding_rate
在模拟盘账户 2 上,市价买 0.001 BTC 现货       → submit_order(FILLED)
平掉我账户 2 的 BTC 多仓                      → close_position
回顾这个月我的组合                            → get_portfolio + get_trade_history
```

**安全边界**:
- AI 调 MCP 工具,实盘下单 / 平仓 / 改风控 / 紧急停止都是两阶段:不带令牌的第一次调用只返预览 + 一次性 `confirmToken`(默认 120s 过期、绑参数指纹、消费即失效),你在对话里显式认可后 AI 才复述相同参数带令牌执行;令牌过期 / 已用 / 参数被改抛 10006
- `apiKey` 在 MCP 工具层剥离,Agent 拿不到
- 涉及 `accountId` 的工具校验归属,越权 1002

## 跑回测

MCP:

```
跑回测:策略 5,BTC/USDT,1h,从 2026-01-01 到 2026-07-01
```
→ `run_backtest(strategyId=5, symbol=BTC/USDT, timeframe=1h, start=..., end=..., params={...})`

返回:`COMPLETED`(结果 JSON)/ `FAILED`(errorMessage)/ `RUNNING`(超时降级,返 taskId 续查,非错误)。

CLI:

```bash
kwikquant backtests -s 5          # 策略 5 的回测历史
kwikquant backtest <taskId>      # 查回测详情
```

对比多次回测走 MCP `compare_backtests(reportIds=[...])`,返排序矩阵。

PERP 策略支持单标的与组合(多标的)回测(保证金/强平近似/资金费回放,语义见 [perp-backtest-spec.md](perp-backtest-spec.md);组合 PERP 见 §10,策略入口 `on_bars(ctx)`)。资金费序列缺期时 fail-closed 拒(7308,errorMessage 带出路):前端/CLI/MCP/REST 提交均可显式开跨所代理 `allowFundingProxy`(Binance 同期次值,报告 warnings 标注基差风险;组合任务逐标的预检,任一标的缺期即拒)。

## 启动 / 停策略

```bash
# 启动(高危,可能启动实盘,须 --confirm;首次必传 -a)
kwikquant strategy start 5 -a 2 --confirm

# 停止 / 暂停(安全,免 confirm)
kwikquant strategy stop 5
kwikquant strategy pause 5

# 重启(高危,须 --confirm;切账户必传 -a)
kwikquant strategy restart 5 -a 5 --confirm
```

MCP:

```
启动策略 5 的模拟盘(账户 2)            → start_paper_trading
启动策略 5 的实盘(账户 5),确认执行    → start_live_trading(先返预览+confirmToken,复述+令牌才执行)
```

**注意**:
- 先回测后实盘,别跳过 `run_backtest` 直接 `start_live_trading`
- 策略创建时绑 `exchange`,启动时账户 `exchange` 须一致,不匹配抛 10002
- `start_live_trading` 真实下单,第二阶段带 `confirmToken` 才执行

## 风控规则与紧急停止

```bash
kwikquant risk policies -a 2     # 查风控规则
kwikquant risk decisions -a 2 --verdict REJECTED --start 2026-08-01T00:00:00Z
# ID  订单ID  账户  决策       规则结果(JSON 数组)  时间
# 8   42      2     REJECTED  [{"ruleType":"MAX_NOTIONAL","passed":false,"reason":"notional 6000 USDT exceeds max 5000 USDT"}]  ...
```

MCP:

```
查我的风控规则                              → get_risk_rules
设置账户 2 单笔最大下单 5000 USDT            → set_risk_rules(params={"maxNotionalUsdt":"5000"})
紧急停止我所有运行中策略,确认执行           → emergency_stop(先返将停策略清单+confirmToken,复述+令牌才执行)
```

返 `{batchUuid, stoppedCount, strategyIds, failedStrategyIds}`。无 RUNNING 策略返 `stoppedCount:0`(非错误)。

**规则类型**:

| ruleType | 含义 | params 示例 |
|---|---|---|
| `MAX_NOTIONAL` | 单笔最大下单额(USDT;PERP 跳过,保证金占用走 `MAX_INITIAL_MARGIN`) | `{"maxNotionalUsdt":"5000"}` |
| `DAILY_LOSS_LIMIT` | 日亏损限额(USDT) | `{"maxLossUsdt":"500"}` |
| `ORDER_FREQUENCY` | 每分钟下单数上限(窗口固定 60s) | `{"maxPerMinute":"10"}` |
| `MAX_INITIAL_MARGIN` | PERP 初始保证金占用 / 可用余额 的上限比例(0-1 小数) | `{"maxInitialMarginRatio":"0.8"}` |

**注意**:
- params 键名必须与上表一致:缺必填键抛 3001,未知键只忽略不报错
- PERP 单在账户没配 `MAX_INITIAL_MARGIN` 时按默认比例 0.8 兜底评一次,别以为没配就不拦
- `set_risk_rules` / `emergency_stop` 都是两阶段:不带 `confirmToken` 先返预览(后者附将停策略清单)+ 令牌,复述参数带令牌才执行
- `emergency_stop` 不可逆(停所有 RUNNING 策略)
- 审计 fail-closed:审计写失败时策略不会被停(宁可不停也不能无审计地停)
- 部分失败可见:返 `failedStrategyIds`,运维须排查未停的策略
