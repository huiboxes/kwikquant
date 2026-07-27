# AGENTS.md

Root instructions for AI coding agents (Claude Code / Codex / Gemini / etc.) working on the KwikQuant monorepo. This file is the shared entry point — Claude Code also loads [`CLAUDE.md`](CLAUDE.md).

## 项目概览

KwikQuant = 加密货币量化交易工作台（单模块 Spring Modulith 单体）。Java 21 + Spring Boot 4.1 + PostgreSQL 16 + MyBatis + CCXT Java。

- 后端：`src/main/java/com/kwikquant/`（单模块，7 个逻辑模块，`@ApplicationModule` 强边界）
- 前端：`frontend/`（React 19 + Vite 8 + TS 6 + Tailwind v4，脚手架已稳）
- 基础设施：`docker/docker-compose.yml`（Postgres 16 + Valkey）

---

## 快速上手命令（必记）

### 后端

```bash
# 首次：起 DB + 编译 + 全测试 + 覆盖率门控（95% 行覆盖硬门控）
./mvnw clean verify

# 日常：只跑测试（跳过 Spotless 格式检查，加速）
./mvnw test -Pno-spotless

# 单测类 / 单测方法
./mvnw test -Dtest=OrderTest -Pno-spotless
./mvnw test -Dtest="OrderTest#cancelledOrder_rejectsTransition" -Pno-spotless

# 一键格式化
./mvnw spotless:apply

# 启动后端（需 Postgres 运行 + .env 配置好）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.useSystemProxies=false -DsocksProxyHost= -DsocksProxyPort= -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.nonProxyHosts=127.0.0.1|localhost|0.0.0.0|::1|*"

# 验证后端
curl --noproxy '*' http://localhost:8080/actuator/health
curl --noproxy '*' http://localhost:8080/v3/api-docs   # OpenAPI 3.0 spec
```

### 前端（`cd frontend`）

```bash
pnpm install
pnpm gen:api        # 从后端 /v3/api-docs 生成 api-gen.ts（需后端跑在 8080）
pnpm dev            # http://localhost:5173，dev proxy 转发 /api /ws → 后端 8080

# 一次性验证
pnpm typecheck && pnpm lint && pnpm test && pnpm build

# CI 门控
pnpm gen:api:check  # git diff --exit-code 拦 api-gen.ts 漂移
pnpm lint:design    # @google/design.md lint DESIGN.md（0 errors）
pnpm lint:design:usage  # 扫硬编码 token 用法
```

---

## 硬约束速查（违反即拒绝 PR / 卡 CI）

| 领域 | 规则 | 执行机制 |
|---|---|---|
| **金额** | 一律 `decimal.js`（`frontend/src/lib/money.ts` 入口：`toDecimal` + `formatMoney`）。禁 `parseFloat`/`Number` 参与金额运算。后端 `BigDecimal` 序列化为带引号 `string`。 | ESLint `no-restricted-syntax` 硬拦 |
| **前端 UI** | 视觉工作必读 [`frontend/DESIGN.md`](frontend/DESIGN.md)（Google DESIGN.md 规范）。Token 流向：`DESIGN.md` → `index.css`（CSS 变量 + Tailwind `@theme inline`） → 组件类。禁止硬编码颜色/圆角/字号。冲突走 §Do's and Don'ts §Agent 实现约束三段式（引用条款 + Token 化替代方案 + 反问用户）。 | `pnpm lint:design` + `pnpm lint:design:usage`（CI 0 errors） |
| **前后端契约** | 契约由后端 OpenAPI 生成（`/v3/api-docs`）。前端跑 `pnpm gen:api` 得到 `src/types/api-gen.ts`。**严禁手写重复类型**。 | `pnpm gen:api:check`（`git diff --exit-code`） |
| **后端模块边界** | Spring Modulith `@ApplicationModule` 强边界，`ArchitectureTests` 测试期强制。`domain/` 严禁依赖 Spring。跨模块只走白名单 `allowedDependencies` + Spring `ApplicationEventPublisher` 事件。 | `ModularityTests` + `ArchitectureTests`（ArchUnit） |
| **测试覆盖** | 后端 JaCoCo 95% 行覆盖硬门控（`./mvnw verify`）。前端 Vitest 边界分支要精准覆盖，不摆设。 | JaCoCo `check` goal（`verify` 阶段） |
| **认证模型** | JWT 存内存（Zustand，不 persist）。WS 走 CONNECT 帧 `Authorization: Bearer`。**不是 cookie**（老前端曾用 cookie，已废弃）。 | 代码审查 + 架构测试 |
| **代码格式** | Palantir Java Format（Spotless）。提交前跑 `./mvnw spotless:apply`。 | `spotless:check`（`verify` 阶段） |

---

## 关键架构事实（不读源码容易踩坑）

### 后端模块依赖图（单向，ArchUnit 守护）

```
shared (types + infra) ← account ← market
                                   ← risk ← trading → market, account, risk
                         notification (shared only)
                         strategy (worker orchestration)
                         report (backtest + portfolio + trade history)
                         mcp (AI PAT tools)
```

### 模块内分层（严格，ArchUnit 守 domain 禁 Spring）

| 层 | 包 | 职责 |
|---|---|---|
| `domain/` | `com.kwikquant.<module>.domain` | 值对象、聚合根、异常。**零 Spring 依赖** |
| `application/` | `com.kwikquant.<module>.application` | 编排服务（模块对外 public API） |
| `infrastructure/` | `com.kwikquant.<module>.infrastructure` | MyBatis Mapper、CCXT 适配器、Spring 配置 |
| `interfaces/` | `com.kwikquant.<module>.interfaces` | REST Controller、DTO、WebSocket 广播 |

### 跨模块通信

- 事件总线：`ApplicationEventPublisher` 发 `OrderStatusChangedEvent` / `RiskTriggeredEvent` / `TickEvent`
- 直接调用：`trading` 调用 `risk` / `market` service（在 `allowedDependencies` 白名单里）

### 关键域模型

- **金额**：全链路 `BigDecimal`（后端）↔ `string`（OpenAPI）↔ `Decimal.js`（前端）
- **Symbol**：CCXT 约定 `BTC/USDT`、`ETH/USDT`。无工具表，动态发现
- **订单状态机**：`NEW → PARTIALLY_FILLED → FILLED / CANCELLED / REJECTED / EXPIRED`
- **三态执行器**：`LiveExecutor`（真实 CCXT）、`PaperExecutor`（本地撮合）、`BacktestExecutor`（回测）

### 安全红线

- API Key **仅在 Java 进程内解密使用**（AES-256-GCM + per-record IV），不落 Worker、不出本地
- RiskGate = 所有下单统一拦截点（手动单、策略信号、SDK/MCP 全走同一管道），fail-closed
- 所有查询强制 `WHERE user_id = #{currentUserId}`（数据隔离）

---

## 前端关键约束（除 DESIGN.md 外）

| 约束 | 细节 |
|---|---|
| **金额红线** | `src/lib/money.ts` 是唯一入口。`parseFloat`/`Number` 参与金额运算被 ESLint 硬拦 |
| **契约链** | `pnpm gen:api` 从后端 `/v3/api-docs` 生成 `api-gen.ts`。**后端窗口生成，前端窗口消费**。前端别跑 `gen:api`（没后端会失败） |
| **PAPER vs 实盘** | 视觉强区分（`live-paper-badge` + 颜色 + 确认弹窗多层防护）。用户绝不能误把实盘当模拟盘下单 |
| **用户旅程** | Dashboard 是主入口，沿 **编码 → 回测 → 模拟 → 实盘** 引导，零割裂。页面结构照 `frontend/prototypes/` 原型实现 |
| **Mono 数字** | 所有数字（价格、涨跌幅、订单簿、持仓、P&L）用 `{typography.font-mono}` + `tnum`/`zero` feature，列对齐、跳动不抖 |

---

## 开发环境坑点（都是踩过的）

| 坑 | 现象 | 解决 |
|---|---|---|
| **`.env` 密码含 shell 特殊字符** | `source .env` 报 `parse error` | 用 `env "KEY=VALUE" ./mvnw ...` 显式传参，或 IDEA EnvFile 插件 |
| **Shell proxy 拦截本地连接** | Clash/V2Ray 设了 `all_proxy`，JVM 继承导致连本地 Postgres 报 `UnknownHostException` | 启动 JVM 显式关 proxy（见启动命令那长串 `-D...`），`curl` 用 `--noproxy '*'` |
| **Colima Testcontainers Ryuk** | Ryuk socket mount 失败 | `pom.xml` surefire 已配 `TESTCONTAINERS_RYUK_DISABLED=true` |
| **Postgres Host** | Colima VM IP 通常是 `192.168.64.2` 而非 `127.0.0.1` | `.env` 里 `POSTGRES_HOST` 填 VM IP，`colima list` 查 |

---

## 常用文件定位

| 需求 | 文件 / 目录 |
|---|---|
| 后端模块边界定义 | `src/main/java/com/kwikquant/<module>/package-info.java` |
| Flyway 迁移 | `src/main/resources/db/migration/V*.sql` |
| OpenAPI 生成入口 | `src/main/java/com/kwikquant/shared/infra/OpenApiConfig.java` |
| 前端 token 定义 | `frontend/DESIGN.md`（YAML 头） |
| 前端 CSS 变量映射 | `frontend/src/index.css` |
| 前端 API 类型生成 | `frontend/src/types/api-gen.ts`（生成，**别手改**） |
| WebSocket 契约 | `docs/ws-contract.md` |
| 端点行为契约 | `docs/behavior-contract.md` |
| 原型（实现照抄） | `frontend/prototypes/` |

---

## 分支与发布约定

- `main`：主线
- `wave1-skeleton` … `wave10-mcp-server`：Wave 分支，合并回 main 后**保留**作历史里程碑
- `frontend-scaffold`：前端脚手架搭建分支

---

## 端口占用

| 端口 | 服务 |
|---|---|
| 5432 | Postgres |
| 6379 | Valkey（Redis-compatible） |
| 8080 | Spring Boot 后端 |
| 5173 | Vite 前端 dev server |

---

## 给 Agent 的工作建议

1. **改后端代码前**：先读对应模块的 `package-info.java` 确认边界，再看 `domain/` 定义异常/值对象，最后动 `application/` 服务。
2. **改前端视觉前**：必读 `frontend/DESIGN.md` 对应段落，找到 token 名，再写 `className`。冲突按三段式处理（引用条款 + Token 替代方案 + 反问）。
3. **加 REST 端点**：后端加 Controller + DTO → 重启后端 → 前端跑 `pnpm gen:api` → 前端用生成的类型。别手写类型。
4. **跑测试前**：后端确认 Docker 跑着（`docker ps` 看 postgres healthy），前端确认后端 8080 可达。
5. **提交前**：后端 `./mvnw spotless:apply`，前端 `pnpm typecheck && pnpm lint && pnpm test && pnpm build` 全绿。

---

## 语言

永远用中文回复。