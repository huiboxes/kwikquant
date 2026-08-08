# Claude Code 安装测试 Checklist

本清单用于验证 KwikQuant MCP server 能被外部 AI 客户端(Claude Code)真实安装并调用 23 个工具。**这是 Step 1"真实落地不虚"的核心验证**——跑通才算 MCP server 真能被消费,简历才敢写。

按顺序勾选。建议先全用**模拟盘账户**,SPOT 起步,PERP 可选。

## 阶段 1:后端就绪

- [ ] PostgreSQL 启动:`docker compose -f docker/docker-compose.yml up -d`
- [ ] 后端启动:`./mvnw spring-boot:run`(等 "Started KwquantApplication")
- [ ] MCP server 暴露验证:`curl -i http://localhost:8080/mcp` 返 401 + `{"code":10001,...}`(PAT filter fail-closed 生效)

## 阶段 2:签发 PAT

- [ ] 登录前端(或 `POST /api/v1/auth/login` 拿 JWT)
- [ ] 签发 PAT:前端 Settings → MCP Tokens 新建,**复制明文 token**(仅此一次)
- [ ] 验证 PAT 列表:`GET /api/v1/mcp/tokens` 返元信息(无明文)

## 阶段 3:配 Claude Code

- [ ] 配置:`claude mcp add --transport http kwikquant http://localhost:8080/mcp --header "Authorization: Bearer <PAT>"`
  - 或编辑 `~/.claude.json` 加 mcpServers(见 install.md)
  - ⚠️ `claude mcp add` 语法以本地 `claude mcp --help` 为准(2026 版本可能有变)
- [ ] 重启 Claude Code
- [ ] 验证已加载:`claude mcp list`(应见 kwikquant)

## 阶段 4:只读工具闭环

在新会话用自然语言测,确认工具被调用(不是 Agent 自己编答案):

- [ ] `列出我的交易所账户` → 触发 `list_accounts`,返账户(无 apiKey)
- [ ] `查 okx 现货 BTC/USDT 最新价` → 触发 `get_ticker`
- [ ] `查 okx 永续 BTC/USDT 资金费率` → 触发 `get_funding_rate`
- [ ] `查 okx 现货 BTC/USDT 最近 1 天 1h K线` → 触发 `get_ohlcv`
- [ ] `账户 X 的余额` → 触发 `get_balances`(X 用 list_accounts 拿到的 id)
- [ ] `我的组合汇总` → 触发 `get_portfolio`

## 阶段 5:下单闭环(模拟盘 SPOT,真实成交)

⚠️ 确保用**模拟盘账户**(paperTrading=true)。

- [ ] `在账户 X(模拟盘)上,okx,市价单买 0.001 BTC/USDT 现货` → 触发 `submit_order`,返 OrderView(FILLED)
- [ ] `查账户 X 持仓` → 触发 `get_positions`,见刚买的 BTC
- [ ] `查账户 X 未成交挂单` → 触发 `get_open_orders`
- [ ] 平掉刚开的仓 → 触发 `close_position`(SPOT 平仓)
- [ ] `查账户 X 交易历史` → 触发 `get_trade_history`,见刚这笔

## 阶段 6(可选):PERP 闭环(模拟盘)

- [ ] `在账户 X(模拟盘)上,okx,10x isolated 做多 0.01 BTC/USDT 永续,市价单` → 触发 `submit_order`(PERP 传 leverage/marginMode/positionEffect=open_long)
- [ ] `查账户 X 持仓` → `get_positions` 见 PERP 仓(含 liquidationPrice / leverage / marginMode)
- [ ] `查账户 X BTC/USDT 资金费历史` → `get_funding_history`(可能空,8h 才结算)
- [ ] 平掉 PERP 仓 → `close_position`(自动派生 CLOSE_LONG)

## 阶段 7(可选):策略 + 风控

- [ ] `跑回测:策略 5,BTC/USDT,1h,从 ... 到 ...` → `run_backtest`,等结果
- [ ] `列出我的回测历史` → `list_backtests`
- [ ] `查我的风控规则` → `get_risk_rules`
- [ ] `紧急停止我所有策略,确认执行` → `emergency_stop(confirm=true)`,返 batchUuid

## 故障排查

见 [install.md 故障排查表](install.md#故障排查)。最常见:
- 工具不出现 → `claude mcp list` 看是否加载,curl /mcp 看 401
- 401/10001 → PAT 没配对 Authorization header
- 1002 → accountId 不是自己的
- 10004 → 高危操作没加 confirm

## 完成标志

阶段 1-5 全勾 = **Step 1 核心闭环验证通过**。简历可写:

> 自研 crypto 量化 MCP Server,远程 Streamable HTTP + PAT 鉴权,23 工具按 5 域打包为 Anthropic Agent Skills;已用 Claude Code 真实安装,跑通行情查询 → 模拟盘下单 → 持仓 → 平仓 → 交易历史全闭环。

阶段 6-7 是加分项。
