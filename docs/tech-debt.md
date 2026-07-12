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

### TD-003 — freezeBalance/unfreezeBalance @Transactional(REQUIRES_NEW) 因同类自调用不生效
- **模块**: trading
- **位置**: `TradingService.java:268-302`
- **问题**: `submit()`/`cancel()` 内部以 `this.freezeBalance(...)` 自调用,Spring AOP 代理拦截不到,`@Transactional` 形同虚设。跟已有的 `insertOrder()` 是同一个模式。
- **影响**: `freezeBalance` 中 `balanceService.freeze()` + `orderMapper.updateFrozenQuoteAmount()` 两步不原子——如果第二步失败,余额已冻结但冻结量未记录,后续走估算兜底。
- **建议**: 抽到独立 `@Service` Bean 使代理生效,或加 `@Lazy` self-inject。
- **优先级**: 低(当前 `submit()` 无外层事务,效果接近逐条 autocommit,跨 bean 场景如 GtdExpirationScheduler 正常生效)

### TD-004 — ExchangeAccountService.create / BalanceService.applyFill 参数过多
- **模块**: account
- **位置**: `ExchangeAccountService.java:48-55`(7 参数 + 4 连续 String)、`BalanceService.java:81-89`(8 参数 + 4 连续 BigDecimal)
- **问题**: 连续同类型参数容易传反位置,编译器无法检测。
- **建议**: 引入 `CreateAccountCommand` / `FillCommand` record 封装。
- **优先级**: 中(改签名影响面大,需专项处理)

### TD-005 — WorkerTokenService.issueToken 组合操作非原子
- **模块**: shared
- **位置**: `WorkerTokenService.java:32-39`
- **问题**: `reverseIndex.get` → `registry.remove` → `registry.put` + `reverseIndex.put` 四步各自原子但组合不原子,同一 strategyId 并发调用会导致 token 泄漏。
- **影响**: 当前调用方(WOS)大概率单线程,低风险。
- **优先级**: 低

### TD-006 — LiveExecutor.confirmCancelled 无 CAS 重试
- **模块**: trading
- **位置**: `LiveExecutor.java:142-154`
- **问题**: CAS 失败时静默跳过,无重试无日志,撤单确认可能丢失。
- **影响**: 实盘(Live)路径未实现(`DefaultCcxtOrderAdapter` 全抛异常),优先级随实盘一起提升。
- **优先级**: 低(blocked by 实盘实现)

### TD-007 — BacktestExecutor 与 BacktestLedger 账本逻辑重复(DRY)
- **模块**: trading
- **位置**: `BacktestExecutor.java:130-176` vs `BacktestLedger.java:32-64`
- **问题**: 新旧回测路径各有一份 BUY/SELL 加仓均价/平仓 PnL 计算,改一处漏另一处。
- **建议**: `BacktestExecutor.runInternal` 改用 `BacktestLedger` 实例跟踪账本。
- **优先级**: 中

### TD-008 — MatchingKernel.walkDepth 流动性不足回退价格对买单乐观
- **模块**: trading
- **位置**: `MatchingKernel.java:103-107`
- **问题**: 剩余 qty 按最后一档价格填充,对买单系统性低估成本。
- **影响**: DEPTH 模式 v1 未启用(PaperExecutor 固定 SPREAD),但 `MatchConfig.depth()` 工厂已暴露。
- **优先级**: 低

### TD-009 — StrategyTools.pollUntilDone 阻塞 MCP 工具线程 60s
- **模块**: mcp
- **位置**: `StrategyTools.java:184-201`
- **问题**: `Thread.sleep(10000)` 轮询最多 6 次,占用线程 60s。
- **建议**: 改为提交后返回 taskId,Agent 主动轮询。
- **优先级**: 中

### TD-010 — ExecutionService.processExecutionReport 隐式依赖 READ_COMMITTED 隔离级别
- **模块**: trading
- **位置**: `ExecutionService.java:82-248`
- **问题**: CAS 重试在同一事务内,依赖 READ_COMMITTED 每条 SELECT 看最新快照。改 REPEATABLE_READ 重试会死循环。
- **影响**: PostgreSQL 默认 READ_COMMITTED,当前安全;代码注释已说明此依赖。
- **优先级**: 低

### TD-011 — WorkerOrchestratorService healthCheck 与 startWorker 竞态
- **模块**: strategy
- **位置**: `WorkerOrchestratorService.java:121-133`
- **问题**: `handleUnhealthy` 的 put+remove 非原子,并发 startWorker 可能被误删。
- **建议**: 用 `ConcurrentHashMap.compute()` 原子操作。
- **优先级**: 低(低频管理操作)

### TD-012 — asBd/toBigDecimal 工具方法在 4 个文件重复实现
- **模块**: market、account
- **位置**: `CcxtTickerWorker:161`、`CcxtKlineWorker:160`、`TradingPairService:126`、`BalanceService:170`
- **问题**: DRY 违反,曾导致 BalanceService 精度 bug(已修,但根因——重复代码——未消除)。
- **建议**: 提取到 `shared` 模块的静态工具方法。
- **优先级**: 低

---

## 已处理

(修复后移至此处)
