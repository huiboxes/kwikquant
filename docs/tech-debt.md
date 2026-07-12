# 技术债清单

> 已识别但暂不修复的问题。修复后删除或移至「已处理」。

---

## 待处理

### TD-001 — refresh_tokens 清理未调度

- **模块**:account
- **位置**:`src/main/java/com/kwikquant/account/infrastructure/RefreshTokenMapper.java:52`
- **问题**:`deleteExpiredAndRevoked()` 已实现但无调用方,撤销/过期的 refresh token 永不删除,表持续膨胀。

### TD-002 — Kline upsert 无法区分实时更新与历史修正

- **模块**:market
- **位置**:`KlineMapper.java` upsert SQL + `V4_1__create_klines.sql`
- **问题**:klines 表无 updated_at/version 字段,upsert 用 GREATEST(high)/LEAST(low) 聚合 + 直接覆盖 close/volume。当交易所修正历史 K 线数据时,无法与正常实时更新区分,可能导致修正值被旧值覆盖或聚合出错误结果。
- **影响**:主流交易对(BTC/USDT 等)修正概率极低;山寨币/新上线交易对风险不为零。
- **建议**:加 updated_at 字段,或在 upsert 中引入版本号;历史回填走独立路径而非复用 watchOHLCV 的 upsert。
- **优先级**:低(短期可接受,Wave4+ 处理)


### RiskGate policy只有三条
- 后续有时间时优化，多新增几条

### 撮合双模式可选
- 目前撮合为了保证三态一致所以在Java撮合，后续如果有参数寻优的需求则加上双模式可选的功能


### CCXT fetchOrderBook 超时：Wave 3 既有 + CCXT 默认 timeout 兜底，MINOR 不修
### MCP E2E 冒烟测试：留 Wave 验证阶段（需 Spring AI MCP client 模拟 Agent）
### 截图双主题/Dashboard/工作区 → 降级为 build+lint+单测等效证据
### E2E 本地跑 → spec 写完，需用户在无代理环境跑

### TD-003 — PortfolioPage EquityCurve 后端无端点
- **模块**:前端 PortfolioPage / 后端 portfolio
- **位置**:`frontend/src/api/portfolio.ts` fetchPortfolioEquityCurve + `frontend/src/test/handlers/portfolio.ts` mock `/portfolio/equity-curve` + `frontend/src/hooks/usePortfolio.ts` usePortfolioEquityCurve
- **问题**:后端 portfolio 域无 equity-curve 端点(equityCurve 字段只在回测报告 schema,api-gen line 1718/3347;PortfolioPnl 只返 positions+totalUnrealizedPnl 单点)。前端建 mock `/portfolio/equity-curve` 端点占位 + useQuery,30 点走势照原型生成。
- **影响**:真实后端集成时曲线为空 or 需补端点。
- **建议**:后端补 `/api/v1/portfolio/equity-curve` 真端点,或澄清曲线来源(可能用 pnl 时间序列?但 pnl 只返单点)。前端只需改 handler + URL,page 不变。
- **优先级**:中

### TD-004 — PortfolioPage 重置 PAPER 后端无 reset 端点
- **模块**:前端 PortfolioPage / 后端 account(paperTrading)
- **位置**:`frontend/src/pages/PortfolioPage.tsx` 重置 PAPER ConfirmDialog(占位 toast.warning)
- **问题**:后端无 reset PAPER 端点(grep 确认只有 paperTrading schema 字段,无 reset 路径)。前端 AlertDialog destructive 占位 toast"待后端提供 reset 端点",不发明组合逻辑(平仓+删单+改余额)。
- **影响**:重置 PAPER 按钮点击只 toast 不生效。
- **建议**:后端补 reset 端点 or 澄清语义(可能组合:平仓+删单+改余额,但前端不发明)。
- **优先级**:中

### TD-005 — PortfolioPage balance 降级语义未 mock
- **模块**:前端 PortfolioPage(测试覆盖)
- **位置**:`frontend/src/test/handlers/portfolio.ts` summary handler
- **问题**:portfolio/summary 端点契约"部分账户失败降级返成功子集",MSW 简化为全成功。真实降级场景(部分 account balance 502)未 mock。
- **影响**:降级路径无测试覆盖。
- **建议**:MSW 补部分账户失败的降级场景 handler + 组件测。
- **优先级**:低

### TD-006 — DashboardPage 聚合指标后端无端点
- **模块**:前端 DashboardPage / 后端 portfolio·dashboard(待建)
- **位置**:`frontend/src/pages/DashboardPage.tsx` HeroCard "7 天 +12.43%" + PerformanceCard 4 Stat(累计收益 / 夏普比率 / 最大回撤 / 胜率)
- **问题**:Dashboard 聚合指标(7 天收益 / 累计收益 / 夏普 / 最大回撤 / 胜率)后端无 dashboard summary 聚合端点。`PortfolioSummary` 只返 `totalUsdt` + `accounts` 余额,`PortfolioPnl` 只返单点 `totalUnrealizedPnl`;回测指标(夏普 / 回撤 / 胜率)只在 `BacktestReportDto` 单报告维度,无跨策略 / 跨时间聚合。前端照原型数字静态占位。
- **影响**:Dashboard 指标区静态展示,不随真实数据变。
- **建议**:后端补 `GET /api/v1/portfolio/dashboard-summary`(聚合 7d/30d 收益 + 累计 + 夏普 + 回撤 + 胜率,跨策略跨账户)or 前端从回测报告 + trade-history 聚合(成本高,推后端)。前端只需接 hook,page 不变。
- **优先级**:中

### TD-007 — DashboardPage 策略行字段缺口(pnl / version / lines)
- **模块**:前端 DashboardPage / 后端 strategy
- **位置**:`frontend/src/pages/DashboardPage.tsx` StrategyRow 持仓盈亏列 + 元信息行
- **问题**:`StrategyDetailDto` 字段 `id/name/description/symbol/exchange/marketType/intervalValue/status/parameters/createdAt/updatedAt`,无 `pnl`(策略持仓盈亏聚合)/ `version`(代码版本)/ `lines`(行数)。原型 `s.timeframe` → `intervalValue` 适配;`version` 占位静态 "v1";`lines` 删(无意义);`pnl` 占位 "—"。`PositionPnl` 无 `strategyId`,无法前端按策略聚合 uPnl。
- **影响**:策略行持仓盈亏列显示 "—";version 显示静态 "v1"。
- **建议**:后端 `StrategyDetailDto` 补 `pnl`/`version`/`lines` 字段(或返策略持仓聚合),前端接字段。或 TradingPage 阶段从 positions + codes 端点聚合(成本高)。
- **优先级**:低(视觉占位可接受,核心交互暂停 / 启动不受影响)

### TD-008 — MarketPage tickers 列表后端无端点
- **模块**:前端 MarketPage / 后端 market
- **位置**:`frontend/src/pages/MarketPage.tsx` MARKET_SYMBOLS + `frontend/src/hooks/useMarket.ts` useTickers
- **问题**:后端无"列表所有 ticker"端点(只有单 symbol GET `/market/ticker/{exchange}/{marketType}/{symbol}`)。前端 hardcode MARKET_SYMBOLS(6 个主流 USDT 对)循环 useTickers(useQueries 批量 GET)。
- **影响**:只能查 hardcode 的 symbol,无法动态发现新交易对;新增 symbol 要改前端代码。
- **建议**:后端补 `GET /market/tickers?exchange=&marketType=`(列表 ticker),或前端从 `/market/pairs` 取 symbol 列表再循环(当前 hardcode 简化)。前端改 hook 即可,page 不变。
- **优先级**:低(主流 symbol 够用)

### TD-009 — MarketPage 订单簿后端无端点
- **模块**:前端 MarketPage / 后端 market
- **位置**:`frontend/src/pages/MarketPage.tsx` OrderBook 组件
- **问题**:后端无 order book / depth 端点(grep market 端点无 orderbook)。OrderBook 硬编码 mock(基于 sel.last 派生 asks/bids 6 行,稳定无随机)。
- **影响**:订单簿不真实(仅展示形态)。
- **建议**:后端补 `GET /market/orderbook/{exchange}/{marketType}/{symbol}?depth=` 或 WS L2 推送。前端接 hook,OrderBook 渲染真数据。
- **优先级**:中(交易核心数据,PAPER 可模拟但 LIVE 需真)

### TD-010 — MarketPage Heatmap 多周期后端无
- **模块**:前端 MarketPage / 后端 market
- **位置**:`frontend/src/pages/MarketPage.tsx` HeatmapSection
- **问题**:后端 ticker 只单点 percentage(当前价 vs open),无多周期(1m/5m/15m/1h/4h/1d)涨跌。Heatmap 多周期用 percentage 派生 mock(`base*0.3+0.4` 等 6 值)。
- **影响**:Heatmap 多周期不真实(单点派生)。
- **建议**:后端补多周期 percentage(ticker 返 changeMap 或 `/market/changes` 多周期端点)。前端接真多周期。
- **优先级**:低

### TD-011 — MarketPage subscribe WS 推送管理未接 marketStore
- **模块**:前端 MarketPage / marketStore
- **位置**:`frontend/src/hooks/useMarket.ts` useSubscribeMarket + `frontend/src/pages/MarketPage.tsx` handleSubscribe + `frontend/src/stores/marketStore.ts`(阶段4 待补)
- **问题**:POST `/market/subscribe` 返占位"订阅已申请",WS 推送管理(tickerTick 由真实成交驱动)推 marketStore 阶段4 补全。当前 POST 后 toast"WS 推送待 marketStore 阶段4 接通",LivePrice 仍 1.8s 定时器闪烁。
- **影响**:订阅不触发 WS 推送,价格闪烁不真实。
- **建议**:marketStore 补 subscribe/unsubscribe + WS 接收 ticker/kline 推送 → 更新 tickerTick + ticker/kline 缓存。前端 POST 后 WS 自动推送。
- **优先级**:中(实时推送核心,定时器兜底可接受)

### TD-012 — MarketPage PAPER 行情来源静态占位
- **模块**:前端 MarketPage
- **位置**:`frontend/src/pages/MarketPage.tsx` PAPER 行情来源卡
- **问题**:PAPER 行情来源(基准 BINANCE / 延迟 12ms / 通道 WS L2)静态占位,后端无端点返这些元数据。
- **影响**:PAPER 来源信息静态,不反映真实延迟。
- **建议**:后端补 `/market/paper-source` 元数据端点,或前端从 `/accounts` PAPER referenceExchange 派生基准 + WS 连接状态。
- **优先级**:低(展示性信息)

### TD-013 — MarketPage 订阅自选列表端点缺失
- **模块**:前端 MarketPage / 后端 market
- **位置**:`frontend/src/pages/MarketPage.tsx` "订阅自选" 按钮(`toast.info` 占位)
- **问题**:原型按钮静态无 onClick,port 加 toast 反馈。但"自选列表"端点缺失,按钮只 toast.info 占位,未像 TD-008~012 记账(本次补记)。
- **影响**:订阅自选功能不可用。
- **建议**:后端补自选列表端点(GET/POST /market/watchlist),或前端 localStorage 存自选 symbol 列表(离线方案)。
- **优先级**:低

### TD-014 — ESLint 金额红线只拦 parseFloat/Number 调用,不拦二元算术
- **模块**:前端 工程基建 / eslint
- **位置**:`frontend/eslint.config.js` `no-restricted-syntax`(只拦 `parseFloat(...)` / `Number(...)` 调用)
- **问题**:CLAUDE.md 金额红线"金额一律 decimal.js,禁 parseFloat/Number 参与金额运算",但 ESLint 规则只拦函数调用,不拦二元算术(`*` / `-` / `+`)。MarketPage OrderBook 买一卖一(`last * 0.9999`)和订单簿 px 派生(`last - (i+1)*0.5`)走 JS number(lint 过但精神违反,reviewer I2 发现,已修 decimal);`Ticker.last` 契约标 number 但运行时是 string,JS 算术靠隐式转换丢 decimal.js 精度保证。
- **影响**:未来其他页金额运算若用 `*`/`-`/`+` 漏网(lint 不拦),靠 reviewer 人工捞。
- **建议**:加 `BinaryExpression[operator='*']` 等选择器,但需细调避免拦合法 number 运算(如 `i * 0.5` 系数派生、`arr.length` 等);或在 `money.ts` 注释强制所有 number-typed 金额字段先进 `toDecimal` 再运算。规则设计需谨慎,先留账。
- **优先级**:中(防金额红线漏网,规则设计需谨慎不误伤)

### TD-015 — BacktestPage 跨策略 RUNNING 任务列表端点缺失
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` list rail(只展 reports)+ `frontend/src/hooks/useBacktest.ts` useReports
- **问题**:原型 list rail 混合 COMPLETED 报告 + RUNNING 任务。后端 `GET /api/v1/backtests?strategyId=` 需 strategyId required(按单策略查任务历史),无"跨策略查所有 RUNNING 任务"端点。`GET /api/v1/reports` 只返 COMPLETED 报告(无 RUNNING)。前端 list rail 只展 reports(COMPLETED),不混 RUNNING。提交回测后轮询 COMPLETED → invalidate reports refetch 自然出现新卡。
- **影响**:list rail 无 RUNNING 任务卡(原型有 progress 进度条卡)。提交后等待期无可见 RUNNING 卡(仅 toast + 后台轮询)。
- **建议**:后端补 `GET /api/v1/backtasks?status=RUNNING`(跨策略查运行中任务,返 BacktestTaskDto[]),或前端提交后临时把 task 加本地 state 展示 RUNNING(简化)。前端接 hook 即可。
- **优先级**:低(提交后 toast + 轮询完成 refetch 可接受,核心交互不受影响)

### TD-016 — BacktestPage 策略行字段缺口(bt.strategy → name)
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` ReportCard `report.name`
- **问题**:`BacktestReportDto` 无 strategyName 字段(只有 name=报告名)。原型 `bt.strategy`(策略名)→ 前端用 `report.name` 展示(报告名,非策略名)。两者语义不同(报告名可能是"BTC/USDT 网格回测",策略名是"BTC Trend Rider")。
- **影响**:list rail 卡标题显示报告名非策略名,与原型语义略偏。
- **建议**:后端 `BacktestReportDto` 补 `strategyName` 字段(或 report.name 约定含策略名)。前端接字段。
- **优先级**:低(展示性差异,核心指标不受影响)

### TD-017 — BacktestPage RUNNING 任务进度条无 progress 字段
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx`(原型 RUNNING 卡有 progress 进度条,本 port 未实现 RUNNING 卡)
- **问题**:`BacktestTaskDto` 字段含 status(PENDING/RUNNING/COMPLETED/FAILED),无 progress 字段。原型 RUNNING 卡展示 `bt.progress%` 进度条。前端无 progress 数据源,且 list rail 不展 RUNNING(TD-015),进度条未实现。
- **影响**:RUNNING 任务无进度可视化(但 list rail 不展 RUNNING,影响小)。
- **建议**:后端 `BacktestTaskDto` 补 `progress` 字段(0-100),或前端用轮询次数/预估时长派生进度(不精确)。前端接字段。
- **优先级**:低(与 TD-015 关联,RUNNING 不展则 progress 无用)

### TD-018 — BacktestPage 对比叠加 EquityCurve 数据缺失
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` CompareTable 叠加 EquityCurveChart
- **问题**:`POST /reports/compare` 返 `ComparisonResultDto.reports: BacktestReportDto[]`(flat,无 equityCurve 字段)。原型对比模式叠加 2 条 EquityCurve(当前 + 对比)。前端无各报告 equityCurve 数据(需 N 次 GET /reports/{id} detail,成本高),用静态数据占位。
- **影响**:对比叠加曲线不真实(静态占位,非各报告真实曲线)。
- **建议**:后端 `ComparisonResultDto` 补 `equityCurves: Map<reportId, EquityPointDto[]>`,或前端对比时并发 GET /reports/{id} 拿各 detail equityCurve(N 个请求,可接受 2-3 个)。前端接数据。
- **优先级**:中(对比模式核心可视化,叠加曲线静态弱化对比价值)

### TD-019 — BacktestPage TradeList pnl/equity 列契约缺失
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` TradeList pnl/equity 列
- **问题**:`TradeRecordDto` 字段 id/time/side/price/amount/fee,无 `pnl`(单笔盈亏)/`equity`(累计权益)。原型 TradeList 6 列含 pnl/equity。前端占位 "—"。
- **影响**:交易明细表 pnl/equity 列空缺(用户看不到单笔盈亏 + 累计权益走势)。
- **建议**:后端 `TradeRecordDto` 补 `realizedPnl`/`equity` 字段,或前端用 price/amount 派生(需成交序列聚合,成本高)。前端接字段。
- **优先级**:中(交易明细核心数据,单笔盈亏是回测分析关键)

### TD-020 — BacktestPage 提交 modal 撮合模式字段后端无
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` SubmitModal 撮合模式 Select(FAST only disabled)
- **问题**:`SubmitBacktestRequest` 字段 strategyId/symbol/exchange/intervalValue/startTime/endTime/parameters,无 `matchMode`(撮合模式)。原型 modal 有"撮合模式 FAST"select。后端回测永远用 FAST(最新价+滑点,behavior-contract §3 提过"回测引擎只有 K 线数据")。前端 Select FAST only disabled(展示性,不可改)。
- **影响**:撮合模式无选择(永远 FAST,符合后端语义)。展示性一致。
- **建议**:无需后端补字段(FAST only 是回测本质)。前端 Select disabled 展示 FAST 即可,或删 Select 改静态文本"FAST 模式"。当前实现 FAST only disabled 可接受。
- **优先级**:低(符合后端语义,FAST only 是设计选择)

### TD-021 — BacktestPage 导入外部报告功能占位
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` Header "导入"按钮(`toast.info` 占位)+ `src/api/backtest.ts` importReport(api 备用)
- **问题**:原型"导入"按钮只 toast 无 handler。后端 `POST /reports/import`(BacktestSubmitRequest: name/params/symbol/timeframe/period/trades/equityCurve)存在。前端按钮 toast.info 占位,未实现文件上传/JSON 粘贴 UI。importReport api 已建(备用)。
- **影响**:导入功能不可用(按钮只 toast)。
- **建议**:前端补导入 Modal(文件上传 or JSON 粘贴 textarea → 解析 → POST /reports/import)。或删按钮(若导入非核心场景)。
- **优先级**:低(导入外部报告是边缘场景,核心是提交回测)

### TD-022 — BacktestPage 列表 Sparkline 数据缺失
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` ReportCard Sparkline(用 SPARK_STATIC 静态数据)
- **问题**:`BacktestReportDto`(列表)无 equityCurve 字段(只有 flat metrics)。原型 list rail 卡右侧 Sparkline 展示收益趋势。前端无列表 equityCurve 数据(需 GET /reports/{id} detail 拿 equityCurve,6 卡 6 次请求),用 SPARK_STATIC 静态数据占位。
- **影响**:列表 Sparkline 不真实(所有卡同一静态曲线)。
- **建议**:后端 `BacktestReportDto` 补 `equityCurveSummary: number[]`(精简趋势点,如 11 点),或前端并发 GET /reports/{id} 拿 equityCurve(N 个请求,列表场景成本高)。前端接数据。
- **优先级**:低(展示性,核心指标 totalReturn 已有)

### TD-023 — BacktestPage 对比表平均持仓列数据缺失
- **模块**:前端 BacktestPage / 后端 backtest
- **位置**:`frontend/src/pages/BacktestPage.tsx` CompareTable "平均持仓"行(占位 "—")
- **问题**:对比表 7 行含"平均持仓"。`BacktestReportDto`(对比 reports)无 `avgTradeDurationSeconds`(该字段在 `MetricsDto` = detail.metrics,BacktestReportDto flat 字段无)。前端对比表"平均持仓"占位 "—"。
- **影响**:对比表平均持仓列空缺。
- **建议**:后端 `BacktestReportDto` 补 `avgTradeDurationSeconds`(flat 字段),或对比 reports 改返 detail(含 metrics)。前端接字段。
- **优先级**:低(对比表核心是收益率/夏普/回撤,平均持仓是辅助指标)

### TD-024 — SettingsPage LLM key 无 active/enabled 字段
- **模块**:前端 SettingsPage / 后端 ai
- **位置**:`frontend/src/pages/SettingsPage.tsx` LlmKeyCard(原型 `k.active && <Chip>● 启用</Chip>` 未实现)
- **问题**:`LlmApiKeyView` 无 `active`/`enabled`/`status` 字段(只有 id/label/provider/apiKeyMasked/baseUrl/createdAt)。原型 LLM key 卡显示"● 启用"徽章。前端不展该徽章。
- **影响**:用户无法在 UI 区分哪些 key 当前启用(LLM key 默认都可用,无启用/停用 toggle)。
- **建议**:后端 `LlmApiKeyView` 补 `active: boolean`(或前端不展,因 LLM key 创建即用)。当前不展可接受(创建即启用是默认语义)。
- **优先级**:低(LLM key 创建即用,无停用语义)

### TD-025 — SettingsPage MCP token 签发 scopes 不传后端
- **模块**:前端 SettingsPage / 后端 mcp
- **位置**:`frontend/src/pages/SettingsPage.tsx` AddMcp modal scopes 网格 + `src/api/mcp.ts` issueMcpToken
- **问题**:`CreateMcpTokenRequest` 只要 `name`(1-64 字符),无 `scopes` 字段。原型签发 modal 有 10 个 scopes 勾选(read_market/read_account/.../emergency_stop/start_live,read_* 默认勾选,高风险标红)。前端 scopes 勾选 UI 保留(照原型设计层),但 issueMcpToken 只传 `{ name }`,不传 scopes。列表卡也不展 scopes(`McpTokenView` 无 scopes 字段)。PAT 是全权限,MCP agent 能做所有操作,高风险(紧急停止/启动实盘)走二次确认 flow 兜底(behavior-contract §4)。
- **影响**:scopes 勾选无实际效果(纯 UI 展示),用户可能误以为限制了 agent 权限。实际 PAT 全权限。
- **建议**:后端 `CreateMcpTokenRequest` 补 `scopes: string[]` 字段(若要真分权),或前端删 scopes 勾选 UI(若 PAT 设计就是全权限)。当前保留 UI + 不传可接受(诚实标注了"高风险走二次确认")。
- **优先级**:中(用户认知偏差风险,但二次确认 flow 兜底高风险)

### TD-026 — SettingsPage 会话管理端点缺
- **模块**:前端 SettingsPage / 后端 auth
- **位置**:`frontend/src/pages/SettingsPage.tsx` account tab "会话"卡(静态硬编码)+ "吊销"按钮(占位 toast.warning)
- **问题**:原型 account tab 有"会话"卡(当前会话 Chrome·macOS + Cursor Agent MCP token,各带"吊销"按钮)。后端无会话列表端点(GET /auth/sessions)也无吊销端点(DELETE /auth/sessions/{id})。前端会话卡静态硬编码(当前会话/Cursor Agent),"吊销"按钮走 ConfirmDialog 占位 toast.warning。
- **影响**:用户无法查看真实会话列表,无法吊销其他设备会话。
- **建议**:后端补 `GET /api/v1/auth/sessions`(返当前用户活跃会话列表:device/lastActive/ip) + `DELETE /api/v1/auth/sessions/{id}`(吊销)。前端接真实数据 + 吊销真调。或删会话卡(若非核心)。
- **优先级**:中(安全相关,用户应能吊销可疑会话)

### TD-027 — SettingsPage 轮换 LLM key 端点缺
- **模块**:前端 SettingsPage / 后端 ai
- **位置**:`frontend/src/pages/SettingsPage.tsx` LlmKeyCard "轮换"按钮(ConfirmDialog 占位 toast.info)
- **问题**:原型 LLM key 卡有"轮换"按钮(只 toast"key 已轮换,旧 key 失效")。后端无 rotate 端点(只有 POST /ai/keys 创建 + DELETE /ai/keys/{id} 删除,无 POST /ai/keys/{id}/rotate)。"轮换"语义 = 删旧 key + 建新 key 两步。前端轮换按钮走 ConfirmDialog 占位 toast.info("轮换需删除旧 key 并重新添加"),不自动删(避免误删用户正在用的 key)。
- **影响**:轮换功能不可用(用户需手动删+建)。
- **建议**:后端补 `POST /api/v1/ai/keys/{id}/rotate`(返新 key 末4位,旧 key 失效)。前端接真调。或前端实现"轮换 = 删旧 + 打开 AddLlm modal 引导重建"组合逻辑(当前不发明组合逻辑,占位 toast)。
- **优先级**:低(轮换是安全最佳实践但非核心,手动删+建可达成)

### TD-028 — SettingsPage telegram/webhook 通知渠道后端支持性未知
- **模块**:前端 SettingsPage / 后端 notification
- **位置**:`frontend/src/pages/SettingsPage.tsx` notif tab 矩阵 4 渠道 + `src/api/notification.ts` upsertNotifPrefs
- **问题**:契约 `PreferenceItem.channelType` 枚举 `WEBSOCKET|EMAIL 等`("等"模糊,只明确 WEBSOCKET/EMAIL)。原型 4 渠道 ws/email/telegram/webhook。telegram/webhook 后端是否支持未知(PUT 传不支持的渠道可能 400)。前端 UI 保留 4 渠道照原型,PUT 只传 WEBSOCKET/EMAIL(明确支持的);telegram/webhook 保持 UI 本地态不 PUT(刷新后回默认)。
- **影响**:telegram/webhook 勾选不持久(刷新丢失),用户可能困惑。
- **建议**:后端澄清 channelType 枚举全集(是否含 TELEGRAM/WEBHOOK)。若支持,前端 PUT 传全 4 渠道;若不支持,前端删 telegram/webhook 列(只留 WEBSOCKET/EMAIL)。
- **优先级**:中(多渠道通知是产品差异化,但当前 WEBSOCKET/EMAIL 已覆盖核心)

### TD-029 — SettingsPage provider 枚举映射中文
- **模块**:前端 SettingsPage / 后端 ai
- **位置**:`frontend/src/api/ai.ts` providerLabel + `frontend/src/pages/SettingsPage.tsx` LlmKeyCard Chip
- **问题**:`LlmApiKeyView.provider` 枚举 `OPENAI|ANTHROPIC|OPENAI_COMPATIBLE`(英文大写)。原型显示中文 "OpenAI"/"Anthropic"/"OpenAI 兼容 (DeepSeek 等)"。前端 `providerLabel(provider)` 映射函数转中文。
- **影响**:无(纯展示映射,功能正常)。
- **建议**:无需后端改。前端映射函数已实现。记录此映射为前端展示层职责(非债,设计选择)。
- **优先级**:无(非债,记录备查)

### TD-030 — SettingsPage auth.ts api 模块只含 changePassword
- **模块**:前端 auth api / 后端 auth
- **位置**:`frontend/src/api/auth.ts`(只有 changePassword)+ `frontend/src/hooks/useAuth.ts`(useLogin/useRegister/useLogout 裸调 /api/v1/auth/*)
- **问题**:plan 阶段 3 列"auth.ts 重构 useLogin/useRegister/useLogout 走 src/api"(当前裸调)。实际只建了 `api/auth.ts` 含 changePassword(SettingsPage 唯一新端点)。login/register/refresh/logout 仍在 hooks/useAuth 裸调(401 单飞续期 + refresh 重放逻辑在 http.ts,见 behavior-contract §1.1)。
- **影响**:auth 调用分散两处(api/auth.ts changePassword + hooks/useAuth 裸调 login/register/refresh),不一致。但功能正常(auth 链已稳,1085 测试过)。
- **建议**:后续重构统一 hooks/useAuth 走 api/auth.ts(补 fetchLogin/fetchRegister/fetchRefresh/fetchLogout)。当前不重构(避免触碰已稳的 auth 链 + 401 单飞逻辑)。
- **优先级**:低(代码一致性,非功能问题,auth 链已稳)

### TD-031 — SettingsPage MCP token 列表明文不可再次查看
- **模块**:前端 SettingsPage / 后端 mcp
- **位置**:`frontend/src/pages/SettingsPage.tsx` McpTokenCard(列表 token 永久 masked)+ AddMcp→McpReveal modal(明文仅签发时显示)
- **问题**:契约 `McpTokenView`(列表)不含明文 token(只有 id/name/createdAt/lastUsedAt/expiresAt/revokedAt)。明文 token 只在 `McpTokenIssueResult`(POST /mcp/tokens 响应)中返回一次,DB 只存哈希。原型 McpTokenCard 有"显示/隐藏"toggle 展示 mock 明文 `kq_live_xxxxxxxxxxxx_{id}`。前端列表卡永久 masked `kq_pat_••••••••••••••••••••••••••••••`,移除 show/hide toggle(契约不支持 reveal existing token)。签发时 McpReveal modal 显示真实明文(一次性,关闭即丢弃)。
- **影响**:用户无法再次查看已签发 token 的明文(符合契约设计,明文 one-time)。原型 show/hide 是 mock 行为,真实场景不可行。
- **建议**:无需后端改(明文 one-time 是安全设计)。前端已诚实处理(永久 masked + 提示"明文仅签发时显示一次")。记录此为契约设计选择(非债)。
- **优先级**:无(非债,契约安全设计,前端诚实处理)

### TD-032 — StrategyPage start 端点契约描述与前端用法不一致(READY vs PAUSED resume)
- **模块**:前端 StrategyPage / 后端 strategy
- **位置**:`frontend/src/api/strategy.ts` startStrategy 注释 + `src/pages/StrategyPage.tsx` handleStart + `src/test/handlers/strategy.ts` start mock
- **问题**:契约 `POST /strategies/{id}/start` 描述"READY→RUNNING 转移,需有发布代码"。前端(DashboardPage + StrategyPage)也用于 PAUSED→RUNNING(resume 语义,运行中⇄已暂停 的恢复路径)。后端无独立 resume 端点。MSW mock 已对齐(接受 READY|PAUSED→RUNNING)。契约描述只说 READY,是否双接 PAUSED 待后端澄清。
- **影响**:若后端 start 严格只接 READY→RUNNING,则 PAUSED→start 会 409(7002),resume 路径断。当前 mock 宽松(接受两者),与契约描述不一致。
- **建议**:后端澄清 start 是否双接 READY+PAUSED(契约描述补"PAUSED→RUNNING resume 语义"),或补独立 `POST /strategies/{id}/resume` 端点。前端按澄清调整。
- **优先级**:中(影响 PAUSED 策略恢复路径,但当前 mock + 前端一致可用)

### TD-033 — StrategyPage StrategyDetailDto 无 version/pnl/lines 字段
- **模块**:前端 StrategyPage / 后端 strategy
- **位置**:`frontend/src/pages/StrategyPage.tsx` Header(version Chip + lines p)+ list rail(pnl)
- **问题**:`StrategyDetailDto` 无 version/pnl/lines 字段。version 从 `useStrategyCodes` list[0].versionNumber 派生(需额外 GET /codes);lines 从 `codeDetail.sourceCode.split('\n').length` 派生(需额外 GET /codes/{codeId});pnl 无端点(runtime 策略 PnL)占位 "— USDT"。
- **影响**:Header version/lines 需 2 次额外请求(codes list + code detail);list rail pnl 列全显 "—"(无 runtime PnL 数据)。
- **建议**:后端 `StrategyDetailDto` 补 `latestVersion` + `codeLines` flat 字段(避免前端额外请求);pnl 补 `pnlUsdt` 字段(或前端订阅 WS /topic/positions 实时算)。前端接字段。
- **优先级**:中(version/lines 可前端派生但多请求;pnl 占位影响 list rail 信息密度)

### TD-034 — StrategyPage StrategyStatusBadge 不覆盖 READY/ERROR 态
- **模块**:前端 StrategyPage / 共享组件 StrategyStatusBadge
- **位置**:`frontend/src/components/StrategyStatusBadge.tsx` MAP(4 态 running/paused/stopped/draft)+ `src/pages/StrategyPage.tsx` toLowerCase 传参
- **问题**:`StrategyStatusBadge` MAP 只有 4 态(running/paused/stopped/draft),契约 `StrategyDetailDto.status` 有 6 态(DRAFT|READY|RUNNING|PAUSED|STOPPED|ERROR)。READY/ERROR 传入时显 neutral 文本 "READY"/"ERROR"(无中文 label,无对应色)。
- **影响**:READY/ERROR 策略的徽章显英文 neutral 色,视觉不一致(其他态显中文+色)。
- **建议**:StrategyStatusBadge MAP 补 READY(就绪/info)+ ERROR(异常/down)两态。共享组件改动需回归 Dashboard/Risk 页。
- **优先级**:低(READY 态短暂即 start;ERROR 罕见;功能不破,视觉略弱)

### TD-035 — StrategyPage AI chat llmKeyId 取首个 key(无选择 UI)
- **模块**:前端 StrategyPage / 后端 ai
- **位置**:`frontend/src/pages/StrategyPage.tsx` AIChat(useLlmKeys 取 [0].id)
- **问题**:AiChatRequest 需 `llmKeyId`。前端 AIChat 从 `useLlmKeys()` 取首个 key 的 id,无选择 UI(用户有多个 LLM key 时无法切换 provider)。无 key 时 toast.warning 提示配置。
- **影响**:多 key 用户无法在 AIChat 切 provider(默认用第一个)。
- **建议**:AIChat header 补 LLM key Select(切换 provider),或在 Settings 默认主 key 标记。当前取首个可接受(单 key 场景正常)。
- **优先级**:低(多 key 用户场景,单 key 正常)

### TD-036 — StrategyPage pnl 无端点占位(同 TD-033,独立记录)
- **模块**:前端 StrategyPage / 后端 strategy/trading
- **位置**:`frontend/src/pages/StrategyPage.tsx` list rail 卡 `— USDT`(pnl 列)
- **问题**:原型 list rail 卡显 `s.pnl`(策略 runtime PnL,mono up/down 色)。契约无策略 PnL 端点(pnl 在 portfolio/summary 或 positions,非 per-strategy)。前端占位 "— USDT"(无色)。
- **影响**:list rail 无 PnL 信息(原型核心视觉元素之一缺失)。
- **建议**:后端补 `GET /strategies/{id}/pnl` 或 `StrategyDetailDto` 加 `pnlUsdt` 字段(订阅 WS 实时)。前端接字段 + mono up/down 色。或前端从 portfolio.pnl.positions 按 strategyId 聚合(复杂)。
- **优先级**:中(list rail 视觉缺失,但非功能阻塞)

### TD-037 — StrategyPage 新建策略占位(无新建 modal)
- **模块**:前端 StrategyPage / 后端 strategy
- **位置**:`frontend/src/pages/StrategyPage.tsx` handleNewStrategy(toast.info 占位)
- **问题**:原型 list rail "+ 新建策略" 卡只 toast("从空白草稿开始")。后端 `POST /strategies` 存在(CreateStrategyRequest)。前端按钮 toast.info 占位,未实现新建 modal(策略名/symbol/exchange/intervalValue 表单)。
- **影响**:新建策略功能不可用(按钮只 toast)。
- **建议**:前端补新建 Modal(策略名 + symbol + exchange + intervalValue + marketType → POST /strategies)。或删按钮(若非核心)。
- **优先级**:低(新建策略可从后端/CLI 完成,前端编辑是核心)

### TD-038 — StrategyPage 启动 modal 绑定账户 select 是 UX only(后端 start 不接 account)
- **模块**:前端 StrategyPage / 后端 strategy
- **位置**:`frontend/src/pages/StrategyPage.tsx` showStart modal 账户 Select(PAPER/LIVE)
- **问题**:原型启动 modal 有"绑定账户"select(PAPER 模拟盘 / LIVE 主账户)。后端 `POST /strategies/{id}/start` 不接 account 参数(策略-账户绑定关系在策略创建时定 or Worker 按用户账户跑)。前端 select 是 UX only(选择不传后端),LIVE 选项触发"需二次确认"提示但无实际 LIVE 启动流程。
- **影响**:用户选 LIVE 以为切实盘,实际 start 不接 account(账户选择无效)。
- **建议**:后端澄清 start 是否接 account(若接,前端传;若不接,删 select 或改显"策略已绑定账户:XXX")。当前 select 是 UX 占位。
- **优先级**:中(PAPER/LIVE 强区分红线相关,select 误导风险)

### PortfolioPage 契约现状(非债,记录备查)
- ExchangeAccountView 无余额字段 → AccountCard 走 per-card GET /accounts/{id}/balance(BalanceSnapshot.currencies{USDT:{free,used,total}})
- ExchangeAccountView 无 market 字段 → honest 删(原型 acc.market 现货/合约下单时选,无需提前绑定)
- 持仓表用 pnl.positions(PositionPnl 含 unrealizedPnl/currentPrice)非 /positions(PositionDto 留 TradingPage)
- 删账户补 ConfirmDialog(原型只 toast,CLAUDE.md 硬要求已补 destructive)

---

## 已处理

(修复后移至此处)
