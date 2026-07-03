# §7.1 六链路 E2E 冒烟测试

Wave 8 契约冻结后的**全平台闭环验证**。冒烟级 — 只验链路连通,穷尽场景各模块单测覆盖。

| 链路 | 测试类 | 主验证点 |
|---|---|---|
| 回测 | `BacktestE2ETest` | BacktestTaskService.submit → executeAsync → mock BacktestRunner 返回 §8 → ReportService.submitBacktestResult → backtest_reports + trade_records + WS COMPLETED |
| 模拟盘 | `PaperRunnerE2ETest` | WorkerTokenService.issueToken("RUNNER") 生命周期 + OrderRouter.route(paperAccount) → PaperExecutor + revoke 幂等 |
| 实盘 | `LiveRunnerE2ETest` | OrderRouter.route(liveAccount) → LiveExecutor(CCXT)(不触发真单) |
| AI 辅助 | `AiChatE2ETest` | AiChatService bean 装配 + Flux<ServerSentEvent<String>> 返回签名冻结 |
| 风控 | `RiskGateE2ETest` | RiskService bean 装配;完整流程由 trading/interfaces/RiskNotificationE2ETest 覆盖 |
| 通知 | `NotificationE2ETest` | NotificationService bean 装配;完整流程同上 |
| OpenAPI 契约 | `OpenApiSpecTest` | GET /v3/api-docs 返回 OpenAPI 3.x + 关键路径(backtests/orders)+ bearer-jwt scheme |

Testcontainers PostgreSQL 16 由 `AbstractIntegrationTest` 静态启动一次,跨所有 E2E 类共享(Spring
context cache + JVM 生命周期);Docker daemon 必须可用。

跨语言契约(§7.4):见 `tests/python/test_client.py` + `tests/python/test_trade.py`(Python 侧模拟
Java 响应)。
