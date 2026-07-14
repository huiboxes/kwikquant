# 前端→后端缺口技术债清单

> **编号声明**：本清单 `FE-TD-xxx` 是前端 agent 记录的「前端页面发现后端缺端点/缺字段」缺口，**与本仓库 `docs/tech-debt.md` 的后端内部债 `TD-001~TD-025` 编号撞号但语义完全不同**。两套清单互不交叉，修复各自独立记账。
>
> 维护规则：前端缺口在后端修复后标「已修复」；纯前端债（本仓库无前端代码）标「前端-only·跳过」。

---

## 已修复

### FE-TD-028 — notification channelType 文档误导 → 已修复（V1 文档对齐）
- **模块**: notification
- **位置**: `notification/interfaces/NotificationPreferenceRequest.java` PreferenceItem.channelType
- **原缺口**: `@Schema` 写「WEBSOCKET | EMAIL 等」，实际 `NotificationChannelType` 仅 `WEBSOCKET`。
- **修复**: `@Schema` 改为如实反映「V1 仅 WEBSOCKET，EMAIL/TELEGRAM/WEBHOOK 暂未实现，传不支持值返 400」。
- **范围**: 仅文档对齐。EMAIL 等渠道实现见 `FE-TD-028-V2`（未做）。

### FE-TD-032 — start Swagger 遗漏 PAUSED resume → 已修复
- **模块**: strategy
- **位置**: `strategy/interfaces/StrategyController.java` start `@Operation`
- **原缺口**: 描述只写 READY→RUNNING，实际 `StrategyLifecycleService.start` 接 READY+PAUSED。
- **修复**: `@Operation` 改为「READY|PAUSED→RUNNING（PAUSED→RUNNING 即 resume，复用同一端点，无独立 resume）」。

### FE-TD-039 — 订单状态契约弱类型 → 已修复（V2 强类型化）
- **模块**: trading
- **位置**: `OrderDetailDto.status` / `OrderEvent.previousStatus,newStatus` / `statusChanged` 工厂 / 5 调用点 + 3 测试
- **原缺口**: DTO/ws 字段为 `String`，`@Schema` 写 6 态用 `PARTIAL`，与运行时 9 态 `OrderStatus.name()` 不符；前端需 `normalizeOrderStatus` 映射层。
- **修复**: `String → OrderStatus` 强类型化，`@Schema` 9 态全枚举；`OrderController.toDto`/`ExecutionService`/`PaperExecutor`/`GtdExpirationScheduler` 去除 `.name()`；`OrderWebSocketBroadcasterTest` 入参改枚举；`OpenApiSpecTest` 重写为断言 OrderStatus 枚举 9 态。
- **验证**: 编译干净；非容器测试 1002/0 failures（`OrderControllerTest` 16/16、`OrderWebSocketBroadcasterTest` 6/6）。**残留**：`OpenApiSpecTest` 9 态断言形态 + 169 Testcontainers 测试需在 Docker 可用环境跑 `./mvnw clean verify` 闭环（本环境 Docker OCI runtime 坏）。

### FE-TD-038 — strategy↔account 单账户不变量 → 已修复（Option B）
- **模块**: account / trading
- **原缺口**: `findByUserAndExchange(userId, exchange)` 多账户场景歧义；`exchange_accounts` 无 `(user_id, exchange)` 唯一约束。
- **架构决策**: 用户拍板「同交易所单账户」→ Option B（非 full-A 显式绑定）。V28 迁移加 `UNIQUE(user_id, exchange)`（含存量重复预检 RAISE，不删数据）；`ExchangeAccountService.create` 加 (userId, exchange) 预检 + insert 包 `DuplicateKeyException` → 409；`findByUserAndExchange`/`OrderController` 注释说明依赖不变量。
- **验证**: `ExchangeAccountServiceTest` 19/19（含 `create_rejectsDuplicateUserExchange` + `create_raceDuplicateInsert_throwsConflict`）；`OrderControllerTest` 16/16。残留：V28 迁移 + 集成测试需 Docker 环境跑。

---

## 已修复（假阳性——前端记录有误，后端原本就有）

| FE-TD | 证据 |
|---|---|
| FE-TD-004 / FE-TD-045 | `POST /api/v1/accounts/{id}/paper/reset`（trading 模块 `PaperAccountController`）已实现清单+清仓+回 10 万 |
| FE-TD-005 | `PortfolioService.getSummary` 逐账户 try/catch 降级逻辑已落地 |
| FE-TD-021 | `POST /api/v1/reports/import` 端点存在 |
| FE-TD-046 | WS `/topic/orders`+`/topic/fills` 推送已实现 |
| FE-TD-048 | `DELETE /api/v1/orders/{id}` 返回 202 已存在 |

---

## 待办（真阳性·后端可修复）

### T2 — DTO 字段补全
- **FE-TD-016**: `BacktestReportDto` + `strategyName`
- **FE-TD-017**: `BacktestTaskDto` + `progress`
- **FE-TD-019**: `TradeRecordDto` + `realizedPnl`/`equity`
- **FE-TD-022**: `BacktestReportDto` + `equityCurveSummary`
- **FE-TD-023**: `BacktestReportDto` + `avgTradeDurationSeconds`（flat）
- **FE-TD-040**: `PositionDto` + `unrealizedPnl`/`currentPrice`
- **FE-TD-007 / FE-TD-033 / FE-TD-036**: `StrategyDetailDto` + `latestVersion`/`codeLines`/`pnlUsdt`；`PositionPnl` + `strategyId`

### T3 — 新增/暴露 REST 端点
- **FE-TD-009 / FE-TD-047**: 暴露 `GET /market/orderbook` REST（`MarketDataService.fetchOrderBook` 已有，仅 MCP 暴露）；K 线 `GET /api/v1/market/klines` 已存在
- **FE-TD-008**: `GET /market/tickers` 列表
- **FE-TD-010**: 多周期涨跌
- **FE-TD-044**: `POST /positions/{id}/close`（平仓=反向单）
- **FE-TD-027**: `POST /ai/keys/{id}/rotate`
- **FE-TD-026**: `GET /auth/sessions` + `DELETE /auth/sessions/{id}`
- **FE-TD-024**（V2）: `LlmApiKeyView` + `active` + `PATCH /ai/keys/{id}/active` toggle

### T4 — 大块新功能（需新表/聚合引擎）
- **FE-TD-003**: `GET /api/v1/portfolio/equity-curve`（需权益快照时间序列持久化）
- **FE-TD-006**: `GET /api/v1/portfolio/dashboard-summary`（跨策略跨账户聚合夏普/回撤/胜率/7d30d 收益）
- **FE-TD-025**: `CreateMcpTokenRequest` + `McpTokenView` 补 `scopes`（PAT 分权重设计）
- **FE-TD-012**: `/market/paper-source` 元数据
- **FE-TD-013**: `/market/watchlist` CRUD（新表）
- **FE-TD-015**: `GET /api/v1/backtasks?status=RUNNING` 跨策略
- **FE-TD-018**: `ComparisonResultDto` + `equityCurves: Map<reportId, EquityPoint[]>`
- **FE-TD-028-V2**: EMAIL/TELEGRAM/WEBHOOK 通知渠道实现（V1 仅文档对齐已做）
- **FE-TD-049**: `POST /auth/oauth/{provider}/start` + `/auth/callback`（Google/GitHub 标准 OAuth2 Authorization Code + PKCE）。需新表 `user_identity(user_id, provider, provider_sub, provider_email)` + Flyway 迁移 + MyBatis mapper。**产品决策待定**：OAuth 用户是否绕过现有邀请码注册流；同邮箱已用密码注册时合并还是拒。**Solana 非 OAuth**——走 SIWS 钱包签名验签（前端 Phantom 适配器 + 后端验签恢复 pubkey），crypto 域单独一套，估 ~2-3 天。前端 `LoginPage`/`RegisterPage` 三社交按钮（Google/GitHub/Solana）已注释为占位，上线前勿开。

---

## 前端-only·跳过（本仓库无前端代码）

FE-TD-005(MSW 降级 mock)、FE-TD-011(marketStore WS 接线)、FE-TD-014(ESLint 金额二元算术)、FE-TD-029(provider 中文映射·非债)、FE-TD-030(auth.ts 重构)、FE-TD-031(MCP token 明文 one-time·非债)、FE-TD-034(StrategyStatusBadge 缺 READY/ERROR 态)、FE-TD-035(AIChat LLM key Select)、FE-TD-037(新建策略 modal·后端 POST /strategies 已在)、FE-TD-041(风控拒跳页无上下文)、FE-TD-042(SPOT/PERP·照原型)、FE-TD-043(symbol select·照原型)、FE-TD-020(撮合 FAST only·非债设计)

---

## 非债/设计选择（记录备查）
- **FE-TD-020**: 回测撮合永远 FAST（`MatchConfig.defaults()` 硬编码 `MatchingFidelity.FAST`，`MatchingFidelity` javadoc「回测仅有 K 线数据」）。V2 双模式可选属另一条后端债（见 `docs/tech-debt.md` 关联）。
- **FE-TD-029 / FE-TD-031**: 非债，前端展示映射 / 契约安全设计。
