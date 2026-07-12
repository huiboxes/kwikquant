# 卓越工程巡检决策记录 (2026-07-12)

## 巡检范围
全后端 9 个 Spring Modulith 模块 (shared/account/market/trading/risk/report/strategy/mcp/notification)

## HIGH 级别问题处理决策

| # | 模块 | 问题 | 决策 | 理由 |
|---|------|------|------|------|
| S-H1 | market | StompSubscriptionInterceptor 拦截所有 STOMP 订阅(行情 topic 尾段非数字 parse 失败) | **修** | 功能性 bug,WS 行情推送完全失效 |
| S-H2 | account | BalanceService.toBigDecimal 用 doubleValue() 造成余额精度丢失 | **修** | 金融数据精度 bug,一行修复 |
| T-H1 | trading | OrderController 直接注入 OrderMapper/FillMapper 绕过 TradingService(层次穿透) | **修** | 违反分层架构,TradingService 已有对应方法 |
| T-H2 | trading | PositionController 直接注入 PositionMapper 绕过 PositionService | **修** | 同 T-H1 |
| T-H3 | trading | MatchingKernel.matchLimit SPREAD 模式用 last 触发限价单(应该用 bid/ask) | **修** | 撮合逻辑不一致,模拟盘表现系统性优于实盘 |
| R-H1 | strategy | AnthropicAdapter 把 system 消息放入 messages 数组(Anthropic API 400) | **修** | 功能性 bug,Anthropic provider 完全不可用 |
| R-H2 | strategy | LLM adapter WebClient 无超时配置,SSE 流可能无限挂起 | **修** | 资源泄漏风险 |
| R-H3 | strategy | RealSubprocessExecutor waitFor 在读 stdout 前,>64KB 输出死锁 | **修** | 正常回测结果会超 64KB |
| R-H4 | report | TradeHistoryService.query 多账户分页逻辑错误 | **修** | 数据正确性 bug |
| R-H5 | mcp | MarketDataTools getOrderbook/getFundingRate 未判空 NPE | **修** | 同类已知 getTicker bug |

## MEDIUM 级别问题处理决策

| # | 决策 | 理由 |
|---|------|------|
| WorkerTokenService TOCTOU | 记入技术债 | 当前单线程调用,低风险 |
| SecurityErrorHandler 死代码 | **修** | 一次性删除,零风险 |
| asBd/toBigDecimal DRY 违反 | **修** | 配合 S-H2 一起抽工具方法 |
| ExchangeAccountService.create 7参数 | 记入技术债 | 改签名影响面大,需专项处理 |
| BalanceService.applyFill 8参数 | 记入技术债 | 同上 |
| KeyManagementService.rotateKey 非原子 | **修** | 加 synchronized 一行修复 |
| 密钥 byte[] 未清零 | **修** | 安全加固,改动极小 |
| LiveExecutor.confirmCancelled 无重试 | 记入技术债 | 实盘未实现,优先级低 |
| BacktestLedger 并发风险 | 记入技术债 | 当前单线程调用 |
| ExecutionService CAS 隔离级依赖 | 记入技术债 | 加文档注释 |
| MatchingKernel walkDepth 回退价 | 记入技术债 | DEPTH 模式 v1 未启用 |
| TradingService.submit 方法超长 | 记入技术债 | 刚做完重构,再拆范围太大 |
| BacktestExecutor DRY 违反 | 记入技术债 | 新旧回测路径共存是已知设计债 |
| MatchingKernel 注释与实现矛盾 | **修** | 删误导注释,一行 |
| WorkerOrchestratorService 竞态 | 记入技术债 | 低频管理操作 |
| NotificationService 无 channel 异常隔离 | **修** | 防御性编程,改动小 |
| StrategyTools pollUntilDone 阻塞 | 记入技术债 | 需要改 MCP 契约 |
| PortfolioService catch(Exception) 过宽 | **修** | 改一行 catch 类型 |
