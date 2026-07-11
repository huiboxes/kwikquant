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

### PortfolioPage 契约现状(非债,记录备查)
- ExchangeAccountView 无余额字段 → AccountCard 走 per-card GET /accounts/{id}/balance(BalanceSnapshot.currencies{USDT:{free,used,total}})
- ExchangeAccountView 无 market 字段 → honest 删(原型 acc.market 现货/合约下单时选,无需提前绑定)
- 持仓表用 pnl.positions(PositionPnl 含 unrealizedPnl/currentPrice)非 /positions(PositionDto 留 TradingPage)
- 删账户补 ConfirmDialog(原型只 toast,CLAUDE.md 硬要求已补 destructive)

---

## 已处理

(修复后移至此处)
