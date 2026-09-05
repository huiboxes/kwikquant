# 六链路 E2E 冒烟测试

**全平台闭环验证**。冒烟级 — 只验链路连通,完整场景由各模块单测覆盖。

| 链路 | 测试类 | 主验证点 |
|---|---|---|
| 回测 | `BacktestE2ETest` | BacktestTaskService.submit → executeAsync → mock BacktestRunner 返回回测结果 JSON → ReportService.submitBacktestResult → backtest_reports + trade_records + WS COMPLETED |
| 模拟盘 | `PaperRunnerE2ETest` | WorkerTokenService.issueToken("RUNNER") 生命周期 + OrderRouter.route(paperAccount) → PaperExecutor + revoke 幂等 |
| 实盘 | `LiveRunnerE2ETest` | OrderRouter.route(liveAccount) → LiveExecutor(CCXT)(不触发真单) |
| AI 辅助 | `AiChatE2ETest` | AiChatService bean 装配 + Flux<ServerSentEvent<String>> 返回签名冻结 |
| 风控 | `RiskGateE2ETest` | RiskService bean 装配;完整流程由 trading/interfaces/RiskNotificationE2ETest 覆盖 |
| 通知 | `NotificationE2ETest` | NotificationService bean 装配;完整流程同上 |
| OpenAPI 契约 | `OpenApiSpecTest` | GET /v3/api-docs 返回 OpenAPI 3.x + 关键路径(backtests/orders)+ bearer-jwt scheme |

PostgreSQL 16 由 `AbstractIntegrationTest` 经 `TestDatabase.resolve()` 静态启动一次,跨所有 E2E 类共享
(Spring context cache + JVM 生命周期)。默认走 Testcontainers,需要 Docker daemon;设置
`KQ_TEST_DB_URL` 后改连本机原生 PostgreSQL,每次运行创建全新随机 schema 隔离,供 Docker 不可用的
受限环境使用。

跨语言契约:见 `tests/python/test_client.py` + `tests/python/test_trade.py`(Python 侧模拟
Java 响应)。
