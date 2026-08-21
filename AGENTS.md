# AGENTS.md

KwikQuant 仓库级 Agent 指令。始终用中文回复；以代码、构建配置和测试为准，`README.md`、`CLAUDE.md`、`docs/ONBOARDING.md` 存在历史漂移，不能单独作为事实源。`LOCAL_DEV.md` 是被忽略的单机备忘，使用前要重新验证环境。

## 仓库边界

- Java 后端是一个 Maven module、单 jar 部署的 Spring Modulith，不是 Maven 多模块项目。入口：`src/main/java/com/kwikquant/KwikquantApplication.java`。
- 后端当前有 9 个模块：`shared`、`account`、`market`、`risk`、`trading`、`report`、`strategy`、`notification`、`mcp`。依赖白名单只认各模块根部 `package-info.java`。
- `frontend/` 和 `cli/` 是两个独立 pnpm 项目，各有 lockfile，不是 pnpm workspace；命令必须在对应目录执行。
- `kwikquant_worker/` 是 Python 策略运行时；`kwikquant/` 是 Python SDK/CLI。两者由根 `pyproject.toml` 一起打包。
- `docker/docker-compose.yml` 当前只启动 PostgreSQL 16；不要根据旧文档假设存在 Valkey 服务。

## 后端命令

```bash
# 快速迭代；仍会运行选中的 Testcontainers 测试
./mvnw test -Pno-spotless
./mvnw test -Dtest=OrderTest -Pno-spotless
./mvnw test -Dtest="OrderTest#cancelledOrder_rejectsTransition" -Pno-spotless

# 模块/分层边界，不需要数据库
./mvnw test -Dtest=ModularityTests,ArchitectureTests -Pno-spotless

# 提交前：格式化，再跑完整门禁
./mvnw spotless:apply
./mvnw clean verify
```

- `clean verify` 包含单测、PostgreSQL Testcontainers 集成测试、Modulith/ArchUnit、Spotless 和 JaCoCo；Docker daemon 必须可用，但无需先启动 compose 数据库。
- JaCoCo 的 95% 是 `pom.xml` 排除列表之后的 bundle 行覆盖率，不代表全部源码 95%；不要为过门禁随意新增 exclude。
- 集成测试继承 `AbstractIntegrationTest`，其静态初始化会为整个 JVM 启动一个共享 PostgreSQL 容器。Docker 不可用时，相关类会在加载阶段失败。
- Surefire 已关闭 JVM 本地代理并设置 `TESTCONTAINERS_RYUK_DISABLED=true`；不要在测试命令里重复拼代理参数。
- Java 格式是 Palantir Java Format。`.githooks/pre-commit` 只会格式化并重新暂存 Java 文件，而且仅在仓库配置了该 hook 时生效。

## 本地启动

```bash
docker compose -f docker/docker-compose.yml up -d
./scripts/start-backend.sh
curl --noproxy '*' http://localhost:8080/actuator/health
curl --noproxy '*' http://localhost:8080/v3/api-docs
```

- Spring Boot 不自动读取 `.env`；`scripts/start-backend.sh` 按原值加载 `KEY=VALUE`，可处理不适合 `source .env` 的特殊字符。
- `.env.example` 是生产模板，包含 `SPRING_PROFILES_ACTIVE=prod`；本地开发不要盲目复制，必须确保实际 `.env` 使用 `dev`，并配置 `POSTGRES_*`、`JWT_SECRET`、`ENCRYPTION_KEY`、`KWIKQUANT_MCP_PEPPER`。
- `application-dev.yaml` 的默认 Worker Python 路径是开发者机器的 macOS 绝对路径；Linux/其他机器必须设置 `KWIKQUANT_WORKER_PYTHON`。
- Shell 代理可能劫持 localhost；`scripts/start-backend.sh` 已关闭 JVM 系统/SOCKS 代理，HTTP 探活仍使用 `curl --noproxy '*'`。
- Flyway 迁移只追加新的 `V*.sql`，不要修改已应用迁移或用 `repair` 掩盖真实 schema 漂移。

## 前端与 CLI

在 `frontend/`：

- 要求 Node `>=22.12.0`、pnpm `10.33.0`；版本以 `frontend/package.json` 为准。

```bash
pnpm install --frozen-lockfile
pnpm typecheck && pnpm lint && pnpm test && pnpm build
pnpm lint:design && pnpm lint:design:usage && pnpm lint:ws

# 聚焦测试
pnpm exec vitest run src/lib/money.test.ts
```

- UI 改动前先读 `frontend/DESIGN.md`。Token 只能按 `DESIGN.md -> src/index.css -> 组件类` 流动；不要硬编码颜色、字号和圆角。设计冲突按 DESIGN.md 的 Agent 拦截流程处理。
- 后端金额运算用 `BigDecimal`；前端金额运算只经 `src/lib/money.ts` 和 `decimal.js`。禁止用 `Number()`/`parseFloat()` 参与金额运算；当前生成契约中仍可能出现 `number`，不要误称网络层已统一为字符串。
- REST 类型唯一来源是后端 OpenAPI 生成的 `frontend/src/types/api-gen.ts`，禁止手改或另写重复 DTO。
- 后端契约改动后，可启动后端再运行 `pnpm gen:api`。无运行中后端时，先在根目录运行 `./mvnw test -Dtest=OpenApiSpecTest -Pno-spotless` 生成 `target/api-spec.json`，再在 `frontend/` 运行 `KWIKQUANT_API_DOCS=../target/api-spec.json pnpm gen:api`；根目录的 `./scripts/check-frontend-codegen.sh` 只做独立生成、类型检查和字段抽样，不会更新已提交类型。
- WS 类型在 `frontend/src/types/ws.ts`，契约在 `docs/ws-contract.md`；任一侧改动都跑 `pnpm lint:ws`。

在 `cli/`：

```bash
pnpm install --frozen-lockfile
pnpm typecheck && pnpm build
```

## Python

```bash
python3 -m venv .venv-worker
.venv-worker/bin/pip install -r requirements-worker.txt
.venv-worker/bin/python -m pytest tests/python
```

- 不要假设系统 Python 已安装 pytest 或满足 `>=3.11`；Worker 与 SDK 的依赖以 `requirements-worker.txt` 和 `pyproject.toml` 为准。
- Worker 只能通过 `X-Worker-Token` 调 Java；交易所 API Key 只允许在 Java 进程内解密，不能传入 Worker、前端、SDK 或日志。

## 架构与安全红线

- 改后端前先读目标模块 `package-info.java`。跨模块直接调用必须在 `allowedDependencies` 中；需要同步返回值的流程用 application service，纯通知优先用 `ApplicationEventPublisher`。
- `domain/` 不得依赖 Spring；由 `ArchitectureTests` 强制。不要假设其他四层依赖方向已被 ArchUnit 完整守护。
- MyBatis 是持久化层，不是 JPA。租户隔离不能机械假设每条 SQL 都有 `user_id`：部分交易表按 `account_id` 查询，入口必须先验证账户/资源归属。
- 所有下单入口，包括手工、策略、SDK 和 MCP，都必须经过 `TradingService`/RiskGate；禁止从新入口直接调用 `Executor` 绕过 fail-closed 风控。
- 浏览器 access token 仅存 Zustand 内存，refresh token 是 httpOnly cookie。浏览器 WS 在 HTTP 握手阶段用 refresh cookie；后端不读取 STOMP CONNECT 的 Bearer。Worker 使用 `X-Worker-Token`，MCP 使用 PAT。
- PAPER/LIVE 由绑定的 `ExchangeAccount.paperTrading` 决定，不是 `strategy.exchange == PAPER`。任何 UI 和业务判断都必须保持模拟盘与实盘强区分。
- `PaperExecutor`、`LiveExecutor`、回测只共享部分接口/撮合规则：实盘由交易所撮合，回测有 NEXT_BAR 等时间语义。不要宣称三者执行行为完全一致。
- 交易、资金、订单状态机、幂等和事务边界的修改必须先补可复现测试；不要只因方法长或参数多重构 `TradingService`、`ExecutionService`、撮合或回测账本。

## CI 与发布事实

- `.github/workflows/ci.yml` 只跑后端 `./mvnw clean verify`。
- `frontend-design-lint.yml` 只跑 DESIGN、设计 token、WS 契约检查；前端 typecheck/ESLint/Vitest/build、Python tests 和 CLI build 目前不是 CI 门禁，相关改动必须本地补跑并报告结果。
- `security-scan.yml` 每日、手动及 `v*` tag 运行 OWASP 依赖扫描，CVSS `>=8` 失败；它与镜像发布是独立 workflow。
- `docker-publish.yml` 在 `v*` tag 上构建并推送 app/worker/frontend 镜像，但自身跳过测试且不等待安全扫描；打 tag 前必须确认 main CI 和受影响的非后端验证均通过。
