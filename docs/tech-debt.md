# 技术债清单

> 已识别但暂不修复的问题。修复后删除或移至「已处理」。

---

## 待处理

### RiskGate policy只有三条
- 后续有时间时优化，多新增几条

### 撮合双模式可选
- 目前撮合为了保证三态一致所以在Java撮合，后续如果有参数寻优的需求则加上双模式可选的功能

### CCXT fetchOrderBook 超时：Wave 3 既有 + CCXT 默认 timeout 兜底，MINOR 不修
### MCP E2E 冒烟测试：留 Wave 验证阶段（需 Spring AI MCP client 模拟 Agent）
### 截图双主题/Dashboard/工作区 → 降级为 build+lint+单测等效证据
### E2E 本地跑 → spec 写完，需用户在无代理环境跑

### TD-008 — MatchingKernel.walkDepth 流动性不足回退价格对买单乐观
- **模块**: trading
- **位置**: `MatchingKernel.java:103-107`
- **问题**: 剩余 qty 按最后一档价格填充,对买单系统性低估成本。
- **影响**: DEPTH 模式 v1 未启用(PaperExecutor 固定 SPREAD),但 `MatchConfig.depth()` 工厂已暴露。
- **优先级**: 低

### TD-016 — LiveExecutor.confirmCancelled 无调用者，Live 撤单路径不闭环 → 部分缓解
- **模块**: trading
- **位置**: `LiveExecutor.java:147`（方法定义），全文无调用者
- **问题**: Live 模式 cancel 只发 CCXT cancelOrder 请求，依赖 WS 确认——但 ensureWsSubscription 仅订阅 fills，未订阅 order status updates。confirmCancelled 已实现 CAS 重试但从未被调用，Live 撤单永远停在 PENDING_CANCEL。
- **本轮缓解**: GTD scheduler 现在扫描 PENDING_CANCEL 订单并推到 EXPIRED + unfreeze，作为超时兜底。startupSnapshot 反向对账仍需 CCXT WS order status 订阅。
- **建议**: CcxtOrderAdapter 增加 subscribeOrderUpdates 接口；回调中对 cancel 确认事件调 confirmCancelled。
- **优先级**: 高（blocked by CCXT WS order status 订阅实现）

### TD-017 — Paper GTC stop/take-profit/trailing 订单永不触发致冻结额永久泄漏 → 已修复
- **修复**：TradingService.submit 中拦截 Paper+GTC+条件单组合，fail-fast reject 并提示用户使用 GTD 或 LIMIT/MARKET。OrderType.isConditional() 辅助方法统一判断条件单类型。

### TD-018 — PaperExecutor.submit PENDING_NEW→SUBMITTED 恢复失败致死路 → 已修复
- **修复**：PENDING_NEW→SUBMITTED 改为完整 CAS 重试循环（MAX_CAS_RETRIES=3），每次重读最新状态后重试。消除单次恢复失败导致订单卡死在 PENDING_NEW 的风险。

### TD-019 — BUY partial fill frozenQuoteAmount 按比例解冻 → 已修复
- **修复**：ExecutionService.computeProportionalFrozen 按 fillQty/totalQty 比例计算每次 applyFill 应解冻量，防止 N 次 partial fill 释放 N×整单冻结额。当前 v1 MatchingKernel 全量成交不触发，启用 partial fill 后生效。

### TD-020 — MARKET BUY ticker null 风控逃逸 → 已修复
- **修复**：TradingService.submit 中 MARKET BUY marketPrice==null 时 fail-fast reject，防止 null notional 绕过风控额度检查。

### TD-021 — DuplicateKeyException TOCTOU 幂等兜底 → 已修复
- **修复**：ExecutionService.processExecutionReport 中 fillMapper.insert 捕获 DuplicateKeyException，降级为 debug 日志+return，避免 WS 重连批量补推时 500 错误。

### TD-022 — ResourceStateConflictException 孤儿 NEW 订单 → 已修复
- **修复**：TradingService.submit 捕获 freezeBalance 抛出的 ResourceStateConflictException（CAS 耗尽），reject 订单避免孤儿 NEW。

### TD-023 — GTD scheduler 漏扫 PENDING_CANCEL + 状态机缺 EXPIRED → 已修复
- **修复**：OrderStatus.PENDING_CANCEL 允许→EXPIRED 转换；findExpiredGtd SQL 加入 PENDING_CANCEL；GTD scheduler 对 PENDING_CANCEL 订单同样执行 unfreeze。

### TD-024 — PaperExecutor cancel CAS 耗尽自愈失效 → 已修复
- **修复**：onTicker 对 PENDING_CANCEL 订单做终态检查+移除，使 GTD expire 推到终态后能从 activeOrders 清理。

### TD-025 — cancel unfreeze guard 条件过宽致正常撤单不解冻 → 已修复
- **修复**：TradingService.cancel 中 unfreeze 前的 latest status 检查从 `isTerminal() && != PENDING_CANCEL` 收窄为仅 `FILLED || PARTIALLY_FILLED`。原条件会把 CANCELLED 也跳过，导致每笔正常模拟盘撤单都不释放冻结额。

### TD-026 — PaperBalanceAdapter.unfreeze .max(ZERO) 静默掩盖余额异常 → 已修复
- **修复**：unfreeze 中 used 被减到负数时先打 WARN 日志（含 accountId/currency/currentUsed/unfreezeAmount），再 clamp 到 ZERO。使余额异常可观测。

### TD-027 — MatchingException 在 infrastructure 层违反六边形架构 → 已修复
- **修复**：MatchingException 迁移至 trading.domain 包，infrastructure 层保留 @Deprecated 兼容类。Order.java 不再反向依赖 infrastructure。

### TD-028 — computeProportionalFrozen 多次等量 partial fill 微量尾差 → 待处理
- **模块**: trading
- **位置**: `ExecutionService.computeProportionalFrozen`
- **问题**: 多次等量 partial fill（如 frozen=100, qty=3, 三次各 fill 1）每次释放 33.33333333，合计 99.99999999，残留 ≈1e-8 在 used 中直到 cancel/reset 才释放。
- **建议**: 最后一笔改用减法兜底（remaining = frozenQuoteAmount - sumOfPriorReleases）。
- **优先级**: 低（金额可忽略，不影响功能正确性）

### TD-029 — MatchingException catch 类型不匹配回归 → 已修复
- **修复**：V-003 迁移 MatchingException 到 domain 层时，ExecutionService.processExecutionReport 中 catch 仍引用 infrastructure 子类，无法捕获 domain 父类异常。over-fill 防御失效→事务回滚→fill 丢失→订单卡死。改为 catch domain.MatchingException + 删除 deprecated 桥接类。Round 3 subagent 确认 0 HIGH。

### TD-030 — WorkerTokenFilter 劫持 JWT 用户的 /api/v1/orders 请求 → 已修复
- **修复**：WorkerTokenFilter.doFilterInternal 改为先检查 `X-Worker-Token` header 是否存在；无该 header 的请求直接放行给后续 filter chain（JwtAuthenticationFilter）。原逻辑 `isWorkerEndpoint(path)` 精确匹配 `/api/v1/orders` 会劫持所有 JWT 用户的下单/列表请求。E2E loop 验证修复后 POST/GET /api/v1/orders 正常通过 JWT 鉴权。

### TD-033 — Logout 不撤销 Access Token，JWT 在有效期内仍可使用 → 已修复
- **修复**：Access token 增加 jti (UUID)；JwtProvider 新增 Caffeine 内存黑名单 (TTL=20min, max=10K)；AuthController.logout 从 Authorization header 提取 access token jti 加入黑名单；JwtAuthenticationFilter 校验时检查黑名单，已撤销则拒绝认证。全部 20 个 auth 测试通过。

### TD-034 — Portfolio Summary 对纯 Paper Trading 用户返回空 → UX 改进
- **模块**: report
- **位置**: `PortfolioService.getSummary()` → `filter(a -> !a.paperTrading())`
- **问题**: Portfolio summary 故意排除 Paper Trading 账户（避免模拟/真实资金混算），但对只有 Paper 账户的用户永远返回 `{accounts:[], totalUsdt:0}`，可能造成困惑。
- **建议**: 返回时增加 `paperAccountsExcluded: true` 提示字段，或在只有 Paper 账户时返回 Paper 余额但标注为模拟。
- **优先级**: 低（UX 改善，不影响功能）
- **发现方式**: E2E loop 验证 portfolio summary 时发现

### TD-035 — TradingService.submit() 143行 / processExecutionReport() 190行方法过长 → 待重构
- **模块**: trading
- **位置**: `TradingService.submit()` (L122-L265), `ExecutionService.processExecutionReport()` (L88-L277)
- **问题**: 两个核心方法超过 80 行阈值。submit 混合了参数校验、风控、冻结、executor 分发；processExecutionReport 混合了幂等检查、CAS 重试、fill 持久化、持仓更新、余额扣减、WS 推送注册。
- **建议**: submit 拆分为 validate/riskGate/freeze/dispatch 子方法；processExecutionReport 提取 CAS 循环体为 private tryApplyFill 方法。
- **优先级**: 中（可读性/可维护性，不影响正确性）
- **发现方式**: excellence-engineer 巡检 V-001/V-002

### TD-036 — 多处 public 方法参数 >3 → 待重构
- **模块**: trading, account
- **位置**: `TradingService.queryOrders(7)`, `countOrders(5)`, `PositionService.applyFill(6)`, `BalanceService.freeze(4)/unfreeze(4)`, `PaperBalanceAdapter.applyFill(7)`, `ExchangeAccountService.update(6)`
- **建议**: 引入 QueryObject/FillApplication/FreezeContext 等参数对象封装。
- **优先级**: 低（代码整洁度，不影响功能）
- **发现方式**: excellence-engineer 巡检

### TD-037 — MarketDataService 应用层直接依赖 8 个 infrastructure 类 → 待重构
- **模块**: market
- **位置**: `MarketDataService.java` imports CcxtExchangeRegistry, CcxtTickerWorker, CcxtKlineWorker, KlineMapper, TickerMapper 等
- **问题**: 应用层应通过 port/interface 依赖基础设施，而非直接引用具体实现类。Worker 创建逻辑尤其应委托给工厂/port。
- **优先级**: 中（架构合规，当前功能不受影响）
- **发现方式**: excellence-engineer 巡检

### TD-038 — BalanceService.createAuthenticatedExchange CCXT SDK 耦合 → 待重构
- **模块**: account
- **位置**: `BalanceService.createAuthenticatedExchange()` 直接 new Binance/Okx/Bitget
- **问题**: 应用层直接实例化交易所 SDK 类，新增交易所需修改此方法。应由 CcxtExchangeRegistry 或工厂统一提供。
- **优先级**: 低（扩展性问题，当前三交易所够用）
- **发现方式**: excellence-engineer 巡检

---

## 已处理

### TD-001 — refresh_tokens 清理未调度 → 已修复
- **修复**：新增 `RefreshTokenCleanupScheduler`（`@Scheduled(fixedDelay=1h, initialDelay=5m)`），调用 `deleteExpiredAndRevoked()`。

### TD-002 — Kline upsert 无法区分实时更新与历史修正 → 已修复
- **修复**：V26 migration 加 `updated_at TIMESTAMPTZ DEFAULT now()` 列；upsert SQL 加 `updated_at = now()`。

### TD-003 — freezeBalance/unfreezeBalance @Transactional(REQUIRES_NEW) 因同类自调用不生效 → 已修复
- **修复**：抽取 `TradingTransactionHelper` 独立 `@Service` Bean，`freezeBalance`/`unfreezeBalance`/`insertOrder` 三个方法迁移至此，Spring AOP 代理正常拦截。`TradingService` 和 `GtdExpirationScheduler` 均改为跨 bean 调用。

### TD-004 — ExchangeAccountService.create 参数过多 → 已修复
- **修复**：引入 `CreateAccountCommand` record 封装 7 参数，Service public 签名改为接收 command。（Round 1 已修 FillCommand，Round 2 补齐 CreateAccountCommand。）

### TD-005 — WorkerTokenService.issueToken 组合操作非原子 → 已修复
- **修复**：`issueToken` 改用 `reverseIndex.compute()` 原子操作，revoke 旧 token + put 新 token 在同一个 compute 块内完成。

### TD-006 — LiveExecutor.confirmCancelled 无 CAS 重试 → 已修复
- **修复**：加重试循环（最多 3 次）+ 状态机拒绝 catch + 重试耗尽 error 日志。

### TD-007 — BacktestExecutor 与 BacktestLedger 账本逻辑重复(DRY) → 已修复
- **修复**：`BacktestExecutor.runInternal` 改用 `BacktestLedger` 实例管理 cash/inventory/avgPrice/pnl，删除内联重复代码。

### TD-009 — StrategyTools.pollUntilDone 阻塞 MCP 工具线程 60s → 已缓解
- **修复**：默认值从 `10s×6=60s` 改为 `3s×5≈15s`。

### TD-010 — ExecutionService.processExecutionReport 隐式依赖 READ_COMMITTED → 已加固
- **修复**：Javadoc @implNote 详细说明隔离级别依赖及修改风险。

### TD-011 — WorkerOrchestratorService healthCheck 与 startWorker 竞态 → 已修复
- **修复**：`handleUnhealthy` 达到 MAX_FAILURES 时改用 `registry.compute()` 原子操作，仅当 containerId 匹配时才移除，避免误删并发 `startWorker` 注册的新容器。

### TD-012 — asBd/toBigDecimal 工具方法在 4 个文件重复实现 → 已修复
- **修复**：提取 `shared.types.NumberUtils.asBd(Object)` 统一实现，4 个文件的局部方法删除并改为 `import static`；`BalanceService.toBigDecimal` 也替换为统一方法。

### TD-013 — TradingService.submit 对 MARKET 单重复查 ticker → 已修复
- **修复**：submit 中提前获取 marketPrice，传入 computeNotional + freezeBalance 共用，消除冗余 I/O。

### TD-014 — TradeHistoryService N+1 fills → 已修复
- **修复**：FillMapper.findByOrderIds 批量查询 + sumVolumeAndFees 聚合 SQL 替代 Java 层 N+1 循环。

### TD-015 — CcxtKlineWorker/CcxtTickerWorker asLong 方法重复 → 已修复
- **修复**：提取到 NumberUtils.asLong，两处 Worker 改 static import。
