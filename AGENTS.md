# AGENTS.md

KwikQuant 仓库级 Agent 指令，仓库事实的唯一真相源：根 `CLAUDE.md` 通过 `@AGENTS.md` 全量导入本文件、只补充 owner 行为准则——新增仓库知识写进本文件对应章节，不要写进 CLAUDE.md。以代码、构建配置和测试为准，`README.md` 存在历史漂移，不能单独作为事实源；首次搭建环境与 `.env`/代理/Docker 坑记见 `CONTRIBUTING.md`。`LOCAL_DEV.md` 是被忽略的单机备忘，使用前要重新验证环境。

## 仓库边界

- Java 后端是单 Maven module 的 Spring Modulith（单 jar 部署）。入口：`src/main/java/com/kwikquant/KwikquantApplication.java`。
- 后端 10 个模块，依赖白名单只认各模块根部 `package-info.java` 的 `allowedDependencies`（`ModularityTests` 机器强制），改后端前先读目标模块的 `package-info.java`。箭头 = 依赖：

```
shared (types + infra)
├─ account      → shared
├─ market       → shared
├─ risk         → shared, account
├─ notification → shared
├─ trading      → shared, account, market, risk
├─ strategy     → shared, account, market, report
├─ report       → shared, account, market, trading
├─ ai           → shared, account, strategy, report, risk
└─ mcp          → shared, account, market, strategy, trading, risk, report
```

- 分层例外：`shared` 只有 `types` + `infra`，`mcp` 只有 `application` + `interfaces`；其余模块都按 `domain / application / infrastructure / interfaces` 分层。
- `frontend/` 和 `cli/` 是两个独立 pnpm 项目（各有 lockfile），命令必须在对应目录执行。
- `kwikquant_worker/` 是 Python 策略运行时，入口 `worker_server.py --mode=backtest|runner`（回测跑完即出结果 JSON，runner 长驻）；`kwikquant/` 是 Python SDK/CLI。两者由根 `pyproject.toml` 一起打包。
- `skills/` 是 MCP Agent Skills 分包，与 mcp 模块的 `@McpTool` 实现对应；改 MCP 工具时同步对应 SKILL.md。
- `docs/` 是面向开发者的接入文档（Markdown + llms.txt）；`api-reference.md` 与 `llms-full.txt` 是生成物——在 `frontend/` 用 `pnpm gen:api:reference` / `pnpm gen:llms-full` 再生成，不要手改。
- `docker/docker-compose.yml` 只启动 PostgreSQL 16，并创建 `kwikquant-worker-net` 桥接网络（Docker 回测/worker 容器依赖，网络须预先存在）。

## 后端命令

```bash
# 快速迭代；仍会运行选中的 Testcontainers 测试
./mvnw test -Pno-spotless
./mvnw test -Dtest=OrderTest -Pno-spotless
./mvnw test -Dtest="OrderTest#transitionTo_fromTerminalThrows" -Pno-spotless

# 模块/分层边界，不需要数据库
./mvnw test -Dtest=ModularityTests,ArchitectureTests -Pno-spotless

# 提交前：格式化，再跑完整门禁
./mvnw spotless:apply
./mvnw clean verify
```

- `clean verify` = 单测 + Testcontainers 集成测试 + Modulith/ArchUnit + Spotless + JaCoCo；需要 Docker daemon，但无需先起 compose 数据库（`AbstractIntegrationTest` 静态启动一个 JVM 共享的 postgres:16 容器）。
- Docker 不可用时设 `KQ_TEST_DB_URL` 切外部原生 PostgreSQL（建库跑 `scripts/setup-local-postgres.sh`）；`scripts/ci-local.sh` 自动探测 Docker，两路等价复现 `ci.yml` 门禁。
- JaCoCo 的 95% 是 `pom.xml` 排除列表之后的 bundle 行覆盖率；不要为过门禁新增 exclude。
- Java 格式是 Palantir；`.githooks/pre-commit`（自动格式化并重新暂存 Java 文件）默认未激活，一次性 `git config core.hooksPath .githooks` 启用。

## 本地启动

```bash
docker compose -f docker/docker-compose.yml --project-directory . up -d
./scripts/start-backend.sh
curl --noproxy '*' http://localhost:8080/actuator/health
curl --noproxy '*' http://localhost:8080/v3/api-docs
```

- Spring Boot 不自动读 `.env`；`start-backend.sh` 负责加载（特殊字符安全）并以 dev profile 启动，必需 secrets（`POSTGRES_*`、`JWT_SECRET`、`ENCRYPTION_KEY`、`KWIKQUANT_MCP_PEPPER`）缺失时启动 fail-fast。
- Shell 代理可能劫持 localhost：探活一律 `curl --noproxy '*'`。交易所连接默认直连，需要代理的环境显式配置 `kwikquant.proxy.defaults`（`ProxyProperties`），不要依赖 shell 代理环境变量。
- Flyway 迁移在 `src/main/resources/db/migration/`，只追加新的 `V*.sql`，不要修改已应用迁移或用 `repair` 掩盖真实 schema 漂移。

## 后端非显然行为

- 行情主通道是 CCXT Pro WebSocket（`watchTicker` / `watchOHLCV`），REST 轮询只是降级 fallback（`MarketFallbackProperties`）；持久化经回调委托 `MarketDataService.onTicker/onKline`。别被 `Ccxt*Worker` 的命名误导成轮询模型。
- 订单状态机含容易漏掉的 `PENDING_CANCEL`；全部状态与合法转移表在 `shared/types/OrderStatus` 的 `ALLOWED`。
- 交易对符号一律 CCXT 格式 `BTC/USDT`（不是 `BTCUSDT`）；库里没有 instruments 表，交易对从交易所动态发现——别找表、别建表。
- REST 统一返回 `ApiResponse<T>` 信封（code/message/data/traceId），错误码是 `ErrorCode` 数字常量；MyBatis 开了 `map-underscore-to-camel-case`，SQL 列 snake_case、Java 字段 camelCase。

## 前端与 CLI

在 `frontend/`：

- Node/pnpm 版本以 `frontend/package.json` 的 engines / packageManager 为准。

```bash
pnpm install --frozen-lockfile
pnpm dev          # :5173，/api、/ws、/ws-native 代理到后端 :8080（须先启动后端）
pnpm typecheck && pnpm lint && pnpm test && pnpm build
pnpm lint:design && pnpm lint:design:usage && pnpm lint:ws
pnpm e2e          # Playwright；webServer 自动起 vite，后端 :8080 须先就绪，未就绪 = fail 而非 skip

# 聚焦测试
pnpm exec vitest run src/lib/money.test.ts
```

- UI 改动前先读 `frontend/DESIGN.md`，它是视觉唯一真相源。Token 只能按 `DESIGN.md -> src/index.css -> 组件类` 流动；不要硬编码颜色、字号和圆角。视觉请求与 DESIGN.md 冲突时不得静默照做：标注冲突置信度、引用违反的具体条款、给出 Token 化替代方案、反问是否需要破例。
- Dev WS 认证：浏览器没带 cookie 时（dev.kwikquant.com 跨域场景）vite proxy 兜底注入 `.env.local` 的 `VITE_DEV_WS_COOKIE`（token 7 天过期需轮换）；WS 401 先查它。
- 后端金额运算用 `BigDecimal`；前端金额运算只经 `src/lib/money.ts` 和 `decimal.js`。禁止用 `Number()`/`parseFloat()` 参与金额运算；当前生成契约中仍可能出现 `number`，不要误称网络层已统一为字符串。
- REST 类型唯一来源是后端 OpenAPI 生成的 `frontend/src/types/api-gen.ts`，禁止手改或另写重复 DTO。
- 后端契约改动后跑 `pnpm gen:api`（需后端在跑）；无后端时先 `./mvnw test -Dtest=OpenApiSpecTest -Pno-spotless` 产出 `target/api-spec.json`，再 `KWIKQUANT_API_DOCS=../target/api-spec.json pnpm gen:api`。根目录 `./scripts/check-frontend-codegen.sh` 只校验，不会更新已提交类型。
- WS 类型在 `frontend/src/types/ws.ts`，契约在 `docs/ws-contract.md`；任一侧改动都跑 `pnpm lint:ws`。

在 `cli/`：

```bash
pnpm install --frozen-lockfile
pnpm typecheck && pnpm build
pnpm test         # tsx --test tests/*.test.ts
```

- 不可逆写操作须 `--confirm`：下单/平仓 LIVE 必须、PAPER 免；`strategy start/restart` 两种模式一律须（可能启动实盘交易）；撤单、`stop`、`pause` 免——明细在 `cli/README.md`。

## Python

```bash
.venv-worker/bin/python -m pytest tests/python   # venv 搭建见 CONTRIBUTING.md;ci.yml python-tests job 门禁
```

- Worker 只能通过 `X-Worker-Token` 调 Java；交易所 API Key 只允许在 Java 进程内解密，不能传入 Worker、前端、SDK 或日志。
- Java `MatchingKernel` 与 Python 侧撮合共享差分 fixtures `tests/fixtures/matching/`（规范 `docs/matching-spec.md`，含 §9 订单接受性：Java `OrderAcceptance`（shared/types，`Order.validate` 委托）与 Python `kwikquant_worker/acceptance.py` 由同目录 `acceptance_*.json`（`kind="acceptance"`）对拍 accepted/reasonCode/message 逐字）；改任一侧撮合或接受性逻辑必须双侧都跑：`./mvnw test -Dtest=MatchingKernelFixturesTest -Pno-spotless` 与 `.venv-worker/bin/python -m pytest tests/python/test_matching_fixtures.py`。
- PERP 回测（单标的净持仓账本/bar 极值强平近似/资金费事件回放（精确 funding_time 节点、mark 优先期次行真值）/缺期 fail-closed exit 3→7308）语义唯一真相源是 `docs/perp-backtest-spec.md`，单标的实现在 `kwikquant_worker/backtest/perp_ledger.py`。组合（多标的）PERP 回测（spec §10）走组合账户账本 `perp_ledger.py::PerpPortfolioLedger` + `portfolio.py` PERP 分支：共享现金 + per-symbol 净持仓、CROSS 账户级保证金聚合（隔离 ISOLATED 锁定额）、Model B 单腿脉冲强平（穿仓全平 CROSS 仓，N=1 逐字退化为单标的 CROSS）、per-symbol 资金费回放。SPOT 组合与 PERP 组合共用联合时间轴引擎。
- 回测时间轴是 BAR/FUNDING 节点归并的事件流（不是逐 bar 批处理）；策略可选顶层事件回调 `on_fill`/`on_funding`/`on_liquidation`（不定义不派发；payload dataclass 在 `context.py`，契约与派发时序矩阵在 `docs/strategy-api.md` §8——回测节点内同步有序、runner 经 WS `/topic/fills|liquidations|funding` 异步无序且按绑定 accountId+市场类型+symbol 过滤——topic 是 user 级，账户过滤防 PAPER/LIVE 跨账户泄漏，策略不得依赖回调与 on_bar 的相对顺序）。
- runner 的 on_fill 断线窗口由 `GET /api/v1/worker/fills-since` 周期补拉兜底（RUNNER token 账户服务端收口、fillId 去重进程内 exactly-once——重启不回放、停摆窗放弃重播种防重复风暴；on_funding/on_liquidation 无补拉通道归对账契约）；worker 请求打订单端点时在用户级鉴权之上再收口到 token 绑定账户（防同用户 PAPER runner 撤 LIVE 账户挂单，红线细节见 `docs/behavior-contract.md` worker 通道清单）。
- 策略契约单一真相源是 `kwikquant_worker/context.py`（`StrategyContext` Protocol + `OrderAck` + `normalize_order` 共享校验），策略作者文档在 `docs/strategy-api.md`（三运行时能力矩阵在内）；三个 ctx（回测/组合/runner）同构由 `tests/python/test_context_contract.py` 差分锁死。运行时能力可分叉但须差分测试显式编码、文档矩阵可查：`predicted_funding_rate()` 是 runner-only 预估资金费查询（回测/组合抛 `NotImplementedError`，走实时交易所预估 + 市场模块短 TTL 缓存，**绝不进回测**——预估是指向未来的当期累计值，喂回测即 lookahead，strategy-api §9 写清回测/实盘已知差异不等价）。下单 amount/price **拒 float**（TypeError，金额红线）；`place_order`/`close_position` 返 `OrderAck`（回测 NEXT_BAR 排队回执、filled_* 恒 None；runner filled_* 是提交时点值，成交异步——不要以 filled_qty 判成交）。任务 parameters 经 exec 前注入的模块级 `PARAMS` + `ctx.params` 进策略，非法 JSON fail-closed exit 1。
- runner 的 REST 金额通道全 decimal string：`PositionDto`/`BalanceSnapshot`/`OrderSubmitResult` 金额字段 `@JsonFormat(STRING)` 序列化，Python 侧 `Decimal(str)` 直读不绕 float；runner 权益走 `GET /api/v1/accounts/worker/balance`（RUNNER token only，账户由绑定推导）；V44 策略级 leverage/marginMode 经 `WorkerBootstrapView` 下发为 runner PERP 订单缺省值。
- Java `PerpMath`（shared/types）与 Python 侧 PERP 数学内核 `kwikquant_worker/perp_math.py` 共享差分 fixtures `tests/fixtures/perp/`（规范 `docs/perp-math-spec.md`，保证金/强平价/资金费/持仓增量/张↔币换算的唯一真相源）；改任一侧 PERP 数学必须按规范 §6 流程（先改 spec → 再改 fixtures → 再改双侧）并双侧都跑：`./mvnw test -Dtest=PerpMathFixturesTest -Pno-spotless` 与 `.venv-worker/bin/python -m pytest tests/python/test_perp_math_fixtures.py`。

## 架构与安全红线

- 改后端前先读目标模块 `package-info.java`。跨模块直接调用必须在 `allowedDependencies` 中；需要同步返回值的流程用 application service，纯通知优先用 `ApplicationEventPublisher`。
- `domain/` 不得依赖 Spring；由 `ArchitectureTests` 强制。不要假设其他四层依赖方向已被 ArchUnit 完整守护。
- 租户隔离不能机械假设每条 SQL 都带 `user_id`：部分交易表按 `account_id` 查询，入口必须先验证账户/资源归属。
- 所有下单入口，包括手工、策略、SDK 和 MCP，都必须经过 `TradingService`/RiskGate；禁止从新入口直接调用 `Executor` 绕过 fail-closed 风控。风控不拦退出通道：PERP `CLOSE_*` 与 SPOT 持仓内反向单（保护性类型直接认定；普通 LIMIT/MARKET 需 LONG 持仓 SELL 且 amount ≤ 持仓 qty，超卖与 SHORT 账本异常行的 BUY 不认定）标记 reduce-only，短路 DAILY_LOSS_LIMIT/MAX_NOTIONAL/保证金占用评估（ORDER_FREQUENCY 在线路径有意仍拦；risk service 宕机 bypass 是另一条路，豁免全部规则并留 RISK_BYPASSED 审计，与 PERP 同）。豁免以平台持仓行为限——LIVE 外部充入、无持仓行的资产其 SELL 不豁免（fail-closed）。
- 浏览器 access token 仅存 Zustand 内存，refresh token 是 httpOnly cookie。浏览器 WS 在 HTTP 握手阶段用 refresh cookie；后端不读取 STOMP CONNECT 的 Bearer。Worker 使用 `X-Worker-Token`，MCP 使用 PAT。
- PAPER/LIVE 由绑定的 `ExchangeAccount.paperTrading` 决定（`OrderRouter` 也按它路由 executor），不是 `strategy.exchange == PAPER`。任何 UI 和业务判断都必须保持模拟盘与实盘强区分。
- `PaperExecutor`、`LiveExecutor`、回测只共享部分接口/撮合规则：实盘由交易所撮合，回测有 NEXT_BAR 等时间语义。不要宣称三者执行行为完全一致。
- 交易、资金、订单状态机、幂等和事务边界的修改必须先补可复现测试；不要只因方法长或参数多重构 `TradingService`、`ExecutionService`、撮合或回测账本。

## 提交礼仪

- commit message 用英文 conventional commits，像真人程序员写的：说清做了什么、为什么，不带 `Co-Authored-By` 等工具痕迹。
- 独立改动拆成自然提交。

## CI 与发布事实

- `.github/workflows/ci.yml` 的 build job 跑后端 `./mvnw clean verify`（其中 OpenApiSpecTest 产出 `target/api-spec.json`）；contract-drift job 复用该 spec 门禁四处生成物漂移：frontend `gen:api:check`、cli `gen:types:check`、docs `gen:api:reference:check` / `gen:llms-full:check`；python-tests job 门禁 `pytest tests/python`（无 Docker/secrets，fork PR 安全）。改后端 controller 注解或接入文档后，本地先跑同命令再推。
- `frontend-design-lint.yml` 只跑 DESIGN、设计 token、WS 契约检查；前端 typecheck/ESLint/Vitest/build/e2e 和 CLI build 仍不是 CI 门禁，相关改动必须本地补跑并报告结果。
- `security-scan.yml` 每日、手动及 `v*` tag 运行 OWASP 依赖扫描，CVSS `>=8` 失败；它与镜像发布是独立 workflow。
- `docker-publish.yml` 在 `v*` tag 上构建并推送 app/worker/frontend 镜像，但自身跳过测试且不等待安全扫描；打 tag 前必须确认 main CI 和受影响的非后端验证均通过。
