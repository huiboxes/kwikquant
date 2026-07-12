# 技术债清单

> 已识别但暂不修复的问题。修复后删除或移至「已处理」。

---

## 待处理

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

### TD-004 — ExchangeAccountService.create 参数过多（部分修复）
- **模块**: account
- **位置**: `ExchangeAccountService.java:48-55`（7 参数 + 4 连续 String）
- **问题**: 连续同类型参数容易传反位置,编译器无法检测。`BalanceService.applyFill` 已通过 `FillCommand` record 修复。
- **建议**: 引入 `CreateAccountCommand` record 封装。
- **优先级**: 中(改签名影响面大,需专项处理)

### TD-006 — LiveExecutor.confirmCancelled 无 CAS 重试
- **模块**: trading
- **位置**: `LiveExecutor.java:142-154`
- **问题**: CAS 失败时静默跳过,无重试无日志,撤单确认可能丢失。
- **影响**: 实盘(Live)路径未实现(`DefaultCcxtOrderAdapter` 全抛异常),优先级随实盘一起提升。
- **优先级**: 低(blocked by 实盘实现)

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

### TD-013 — TradingService.submit 对 MARKET 单重复查 ticker
- **模块**: trading
- **位置**: `TradingService.computeNotional` + `TradingTransactionHelper.freezeBalance`
- **问题**: submit 热路径上 computeNotional 和 freezeBalance 各调一次 getLatestTicker，同一 symbol 两次查询。
- **建议**: 将 computeNotional 已获取的价格传入 freezeBalance，消除冗余 I/O。
- **优先级**: 低（单次查询开销小，缓存命中率高）

### TD-014 — TradeHistoryService 多账户分页全量加载 + N+1 fills
- **模块**: report
- **位置**: `TradeHistoryService.java:55,70`
- **问题**: 多账户路径加载所有订单到内存再截断；每笔订单单独查 fills（N+1）。
- **建议**: 推 ORDER BY + LIMIT/OFFSET 到跨账户 SQL；批量查 fills（WHERE order_id IN (...)）。
- **优先级**: 中（数据量大时性能瓶颈）

### TD-015 — CcxtKlineWorker/CcxtTickerWorker asLong 方法重复
- **模块**: market
- **位置**: `CcxtKlineWorker:161`、`CcxtTickerWorker:162`
- **问题**: 与 TD-012 同模式的 DRY 违反，asLong(Object) 两处完全相同。
- **建议**: 提取到 NumberUtils.asLong(Object)。
- **优先级**: 低

---

## 已处理

### TD-001 — refresh_tokens 清理未调度 → 已修复
- **修复**：新增 `RefreshTokenCleanupScheduler`（`@Scheduled(fixedDelay=1h, initialDelay=5m)`），调用 `deleteExpiredAndRevoked()`。

### TD-003 — freezeBalance/unfreezeBalance @Transactional(REQUIRES_NEW) 因同类自调用不生效 → 已修复
- **修复**：抽取 `TradingTransactionHelper` 独立 `@Service` Bean，`freezeBalance`/`unfreezeBalance`/`insertOrder` 三个方法迁移至此，Spring AOP 代理正常拦截。`TradingService` 和 `GtdExpirationScheduler` 均改为跨 bean 调用。

### TD-004 — BalanceService.applyFill 参数过多 → 部分修复
- **修复**：引入 `FillCommand` record 封装 8 个参数为 1 个。`ExchangeAccountService.create` 暂未改（影响面大，需专项处理）。

### TD-005 — WorkerTokenService.issueToken 组合操作非原子 → 已修复
- **修复**：`issueToken` 改用 `reverseIndex.compute()` 原子操作，revoke 旧 token + put 新 token 在同一个 compute 块内完成。

### TD-007 — BacktestExecutor 与 BacktestLedger 账本逻辑重复(DRY) → 已修复
- **修复**：`BacktestExecutor.runInternal` 改用 `BacktestLedger` 实例管理 cash/inventory/avgPrice/pnl，删除内联重复代码。

### TD-011 — WorkerOrchestratorService healthCheck 与 startWorker 竞态 → 已修复
- **修复**：`handleUnhealthy` 达到 MAX_FAILURES 时改用 `registry.compute()` 原子操作，仅当 containerId 匹配时才移除，避免误删并发 `startWorker` 注册的新容器。

### TD-012 — asBd/toBigDecimal 工具方法在 4 个文件重复实现 → 已修复
- **修复**：提取 `shared.types.NumberUtils.asBd(Object)` 统一实现，4 个文件的局部方法删除并改为 `import static`；`BalanceService.toBigDecimal` 也替换为统一方法。
