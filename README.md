# KwikQuant

Cryptocurrency 量化交易后端（单模块 Spring Modulith）。Java 21 + Spring Boot 4.1 + PostgreSQL 16 + MyBatis + CCXT Java。

前端仓在 `frontend/`（脚手架搭建中，见 [`frontend/SCAFFOLD-REF.md`](frontend/SCAFFOLD-REF.md)）。

---

## 二开上手（从零到跑起后端 + 前端）

### 前置要求

| 组件 | 版本 | 校验命令 |
|---|---|---|
| Java | 21+ | `java -version` |
| Maven Wrapper | 项目自带 3.9.9 | `./mvnw --version` |
| Docker | 任意版本（Colima / Docker Desktop 均可） | `docker ps` |
| Node.js | ≥ 22.12 | `node -v` |
| pnpm | 10.33+ | `pnpm -v` |
| OpenSSL | 系统自带 | `openssl version` |

> **Colima 用户注意**：Postgres 会跑在 Colima VM（IP 通常是 `192.168.64.2` 而非 `127.0.0.1`）。`.env` 里的 `POSTGRES_HOST` 要填 VM IP，不能填 `127.0.0.1`。用 `colima list` 或 `colima status` 查 VM IP。

---

### 一、克隆 + 基础设施

```bash
git clone <repo> kwikquant
cd kwikquant

# 起 Postgres + Valkey（Redis-compatible）
docker compose -f docker/docker-compose.yml up -d
docker ps  # 确认 kwikquant-postgres + kwikquant-valkey 都 healthy
```

---

### 二、`.env` 环境变量

```bash
cp .env.example .env
```

**必填项**（否则应用启动 fail-fast）：

| 变量 | 说明 | 生成方式 |
|---|---|---|
| `POSTGRES_HOST` | Postgres 主机 | Docker Desktop 用 `127.0.0.1`；Colima 用 `192.168.64.2` |
| `POSTGRES_PORT` | Postgres 端口 | `5432` |
| `POSTGRES_DB` | 数据库名 | `kwikquant` |
| `POSTGRES_USER` | 用户名 | `kwikquant` |
| `POSTGRES_PASSWORD` | 密码 | 自定义（**含特殊字符请见下方坑记 A**）|
| `JWT_SECRET` | JWT 签名密钥（32 字节 base64） | `openssl rand -base64 32` |
| `ENCRYPTION_KEY` | API key 加密密钥（AES-256-GCM，32 字节 base64） | `openssl rand -base64 32` |
| `KWIKQUANT_MCP_PEPPER` | MCP PAT HMAC pepper（≥ 32 字节高熵字符串） | `openssl rand -base64 32` |

一键生成三个 secret 追加到 `.env`：

```bash
cat >> .env << EOF

JWT_SECRET=$(openssl rand -base64 32)
ENCRYPTION_KEY=$(openssl rand -base64 32)
KWIKQUANT_MCP_PEPPER=$(openssl rand -base64 32)
EOF
```

> 这些是**本地 dev secret**，泄漏无风险，随时可重生。生产/预发走 CI/CD secret 注入，不写文件。

---

### 三、编译 + 测试（首次务必跑）

```bash
./mvnw clean verify
```

这一步会：拉依赖 → 编译 → 跑单元测试 + 集成测试（Testcontainers 会拉 Postgres 16 镜像）→ JaCoCo 覆盖率检查（**95% 行覆盖硬门控**）→ Spotless 格式检查（Palantir Java Format）。

首次约 5–10 分钟。全绿即可进入下一步。

**加速迭代**（跳格式检查）：`./mvnw test -Pno-spotless`

---

### 四、启动后端

**IDEA（推荐日常开发）**：Run/Debug Configuration → Spring Boot → `KwikquantApplication` → Environment Variables 里加载 `.env`（安装 [EnvFile](https://plugins.jetbrains.com/plugin/7861-envfile) 插件）→ Profile `dev`。

**命令行**：

```bash
# 单行版（复制粘贴即可，值来自你的 .env）
env \
  POSTGRES_HOST="192.168.64.2" \
  POSTGRES_PORT="5432" \
  POSTGRES_DB="kwikquant" \
  POSTGRES_USER="kwikquant" \
  "POSTGRES_PASSWORD=<从 .env 复制>" \
  "JWT_SECRET=<从 .env 复制>" \
  "ENCRYPTION_KEY=<从 .env 复制>" \
  "KWIKQUANT_MCP_PEPPER=<从 .env 复制>" \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.useSystemProxies=false -DsocksProxyHost= -DsocksProxyPort= -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.nonProxyHosts=127.0.0.1|localhost|0.0.0.0|::1|*"
```

**为什么这么啰嗦？** 见下方"坑记 B"。

**验证**：

```bash
curl --noproxy '*' http://localhost:8080/actuator/health
# 期望：{"groups":["liveness","readiness"],"status":"UP"}

curl --noproxy '*' -o /tmp/api-docs.json http://localhost:8080/v3/api-docs
# 期望：166KB+ JSON，48+ paths、108+ schemas
```

---

### 五、启动前端

**脚手架未搭完前**（当前状态）：`frontend/` 目录里只有 `DESIGN.md`（视觉契约）和 `SCAFFOLD-REF.md`（搭建清单）。搭建进行时切到 `frontend-scaffold` 分支。

**脚手架搭完后**：

```bash
cd frontend
pnpm install
pnpm gen:api   # 从后端 http://localhost:8080/v3/api-docs 生成 api-gen.ts
pnpm dev       # → http://localhost:5173
```

前端契约链 `gen:api` 硬依赖后端 `/v3/api-docs`。**后端没起就不要退化为 mock，先起后端。**

---

## 日常开发命令

```bash
# 后端
./mvnw test -Pno-spotless                    # 只跑测试（跳格式）
./mvnw test -Dtest=OrderTest -Pno-spotless   # 单类
./mvnw test -Dtest="OrderTest#cancelledOrder_rejectsTransition" -Pno-spotless  # 单方法
./mvnw spotless:apply                        # 一键格式化

# 数据库
docker compose -f docker/docker-compose.yml down    # 停容器（保留数据）
docker compose -f docker/docker-compose.yml down -v # 停容器 + 删数据卷（重置）

# 前端（脚手架搭完后）
pnpm typecheck && pnpm lint && pnpm test && pnpm build   # 一次性验证
pnpm gen:api:check                                       # CI drift 检查
```

---

## 坑记（都是踩过的）

### A. `.env` 密码含 shell 特殊字符

如果 `POSTGRES_PASSWORD` 含 `)` `(` `;` `&` `|` 等 shell 特殊字符，`source .env` 会 `parse error`。原因：POSIX shell 读到 `KEY=value` 时会对 value 做词法解析，遇到特殊字符就崩。

**方案**：
- **IDEA EnvFile 插件**：自动兼容 unquoted，无需处理
- **命令行**：不要 `source .env`，用 `env "KEY=VALUE" ./mvnw ...` 显式传参（Bash `env` 命令的 `KEY=VALUE` 语法整体加双引号即可）
- **或者**：`.env` 里给值加单引号 `POSTGRES_PASSWORD='Y]b-.!a);.)EL...'`

### B. shell proxy 拦截本地连接

macOS 装 Clash / V2Ray 时，shell 里通常有：
```
all_proxy=socks5://127.0.0.1:13659
http_proxy=http://127.0.0.1:13659
https_proxy=http://127.0.0.1:13659
```

JVM 默认继承这些环境变量，导致连本地 Postgres/Redis 时**用 socks proxy 转发**，报 `UnknownHostException: 127.0.0.1`。

**方案**：启动 JVM 时显式关 proxy（就是"启动后端"里那一长串 `-Dspring-boot.run.jvmArguments`）。`pom.xml` 里的 surefire 插件也是同套路。curl 验证时用 `--noproxy '*'`。

**为什么不 unset**：CCXT 访问境外交易所需要 proxy，全 unset 会导致交易所连不上。用 `nonProxyHosts` 白名单本地地址是正确方式。

### C. Testcontainers Ryuk

Colima 环境下 Testcontainers 的 Ryuk（容器清理守护）socket mount 会失败。`pom.xml` surefire 里已 `TESTCONTAINERS_RYUK_DISABLED=true` 禁用，只影响清理不影响测试。

---

## 架构速览

**单模块 Spring Modulith**（不是多 Maven 模块），一个 jar 部署。7 个逻辑模块通过 `package-info.java` 的 `@ApplicationModule` 强边界隔离，`ArchitectureTests` 用 ArchUnit 在测试期强制。

```
shared (types + infra) ← account ← market
                                  ← risk ← trading → market, account, risk
                        notification (shared only)
                        strategy (worker orchestration)
                        report (backtest + portfolio + trade history)
                        mcp (AI PAT tools)
```

每模块内部分层（严格）：
- `domain/` — 值对象、聚合根、异常。**禁 Spring 依赖**（ArchUnit 守）
- `application/` — 编排服务（模块对外的 public API）
- `infrastructure/` — MyBatis Mapper、CCXT 适配器、Spring 配置
- `interfaces/` — REST Controller、DTO、WebSocket 广播

**跨模块通信**：Spring `ApplicationEventPublisher` 派发 `OrderStatusChangedEvent` / `RiskTriggeredEvent` / `TickEvent`。trading 直接调用 risk/market service（在 `allowedDependencies` 白名单里）。

---

## 文档导览

| 目录 / 文件 | 内容 |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | 给 AI 编码 agent 的项目上下文（架构、约束、命令） |
| [`docs/README.md`](docs/README.md) | 权威文档索引 |
| [`docs/product-direction.md`](docs/product-direction.md) | 产品全景 v2.1（唯一权威） |
| [`docs/architecture-and-constraints.md`](docs/architecture-and-constraints.md) | 关键边界与踩坑预警（必读） |
| [`docs/implementation-plan.md`](docs/implementation-plan.md) | Wave 1-10 实施计划 |
| [`docs/ux-design.md`](docs/ux-design.md) | 前端 UX/IA 决策 |
| [`docs/ws-contract.md`](docs/ws-contract.md) | WebSocket 9 topic 契约 |
| [`frontend/DESIGN.md`](frontend/DESIGN.md) | 前端视觉工程契约（token 权威源，Google DESIGN.md 规范） |
| [`frontend/SCAFFOLD-REF.md`](frontend/SCAFFOLD-REF.md) | 前端脚手架搭建清单 |

**分支约定**：`main` 主线；Wave 分支（`wave1-skeleton`... `wave10-mcp-server`）合并回 main 后**保留**作历史里程碑；`frontend-scaffold` 分支搭建前端脚手架。

---

## 端口占用

| 端口 | 服务 |
|---|---|
| 5432 | Postgres |
| 6379 | Valkey（Redis-compatible） |
| 8080 | Spring Boot 后端 |
| 5173 | Vite 前端 dev server（默认） |
