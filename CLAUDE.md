# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KwikQuant is a cryptocurrency quantitative trading backend built as a single-module **Spring Modulith** application (not a multi-module Maven project). Java 21, Spring Boot 4.1, PostgreSQL 16, MyBatis, CCXT Java for exchange connectivity.

## 语言
永远用中文回复

## Build & Test Commands

```bash
# Full build + test + coverage + formatting check
./mvnw clean verify

# Run tests only (skip spotless)
./mvnw test -Pno-spotless

# Run a single test class
./mvnw test -pl . -Dtest=OrderTest -Pno-spotless

# Run a single test method
./mvnw test -pl . -Dtest="OrderTest#cancelledOrder_rejectsTransition" -Pno-spotless

# Format code (Palantir Java Format via Spotless)
./mvnw spotless:apply

# Start dev server (needs PostgreSQL running + .env configured)
./mvnw spring-boot:run

# Start PostgreSQL for local dev
docker compose -f docker/docker-compose.yml up -d
```

## Key Build Details

- **JaCoCo coverage gate**: 95% line coverage enforced at `verify` phase. Certain infra/config classes are excluded (see `pom.xml` exclusions).
- **Spotless**: Palantir Java Format runs on `verify`. Pre-commit hook auto-formats staged files. Use `-Pno-spotless` profile to skip during iterative test runs.
- **Surefire**: Configured to disable proxies for test JVM and disable Testcontainers Ryuk (Colima compatibility).
- **Integration tests** use Testcontainers with PostgreSQL 16. Docker must be running. All integration tests extend `AbstractIntegrationTest` which shares a single container across the test suite.

## Architecture

### Spring Modulith Modules

Single deployment unit with enforced module boundaries via `package-info.java` `@ApplicationModule` annotations. Module dependency graph:

```
shared (types + infra) ← account ← market
                                  ← risk ← trading → market, account, risk
                        notification (shared only)
                        strategy (placeholder)
```

Each module follows a consistent internal layering:
- `domain/` — Pure domain objects, value types, exceptions. **No Spring dependencies allowed** (enforced by ArchUnit).
- `application/` — Services orchestrating domain logic. This is the module's public API.
- `infrastructure/` — MyBatis mappers, CCXT adapters, Spring config, WebSocket config.
- `interfaces/` — REST controllers, DTOs, WebSocket broadcasters.

Module boundary rules are verified at test time by:
- `ModularityTests` — `ApplicationModules.of(...).verify()` checks `allowedDependencies`.
- `ArchitectureTests` — ArchUnit rule: `domain` packages must not depend on Spring.

### Modules

- **shared** — Cross-cutting: typed IDs (record-based value objects like `AccountId`, `OrderId`, `Symbol`), `Exchange` enum, `Interval`, event types (`OrderStatusChangedEvent`, `RiskTriggeredEvent`, `TickEvent`), `ApiResponse` envelope, `ErrorCode` constants, audit infrastructure, security utilities.
- **account** — User auth (JWT access + refresh tokens, `JwtProvider`), exchange account management (API key encryption via AES-256-GCM + `ApiKeyEncryptor`), WebSocket auth interceptor.
- **market** — Market data via CCXT Java: `CcxtTickerWorker`, `CcxtKlineWorker` poll exchange APIs and persist tickers/klines. STOMP WebSocket push for live data. `CcxtExchangeRegistry` manages exchange instances. `MarketProperties` drives exchange/symbol config.
- **trading** — Order lifecycle (submit → NEW → FILLED/CANCELLED), three execution modes via `Executor` interface: `LiveExecutor` (real exchange via `CcxtOrderAdapter`), `PaperExecutor` (simulated matching via `MatchingKernel`), `BacktestExecutor`. Position tracking with delta updates. GTD order expiration scheduler. `OrderRouter` selects executor by exchange. `TradingBootstrap` wires dependencies. WebSocket order/fill/position broadcasting.
- **risk** — Pre-trade risk gate: `RiskService.check()` evaluates `RuleEvaluator` chain (`MaxNotionalEvaluator`, `OrderFrequencyEvaluator`, `DailyLossLimitEvaluator`). `RiskPolicy` CRUD with conflict detection. `RiskDecision` audit log.
- **notification** — Dispatches events to channels (`WebSocketNotificationChannel`). User notification preferences per event type.
- **strategy** — Placeholder module (package-info only).

### Cross-Module Communication

Modules communicate via Spring application events (`OrderStatusChangedEvent`, `RiskTriggeredEvent`, `TickEvent`) published through `ApplicationEventPublisher`. The trading module calls risk and market services directly (allowed by its `allowedDependencies`).

### Canonical Symbol Format

Symbols follow CCXT convention: `BTC/USDT`, `ETH/USDT`. No instruments table — trading pairs are discovered dynamically from exchanges.

### Persistence

- **MyBatis** (not JPA) with XML-free annotation-based mappers.
- **Flyway** migrations in `src/main/resources/db/migration/` (V1–V10).
- All monetary values use `BigDecimal`.
- `map-underscore-to-camel-case: true` — DB columns are snake_case, Java fields are camelCase.

### API Response Envelope

All REST endpoints return `ApiResponse<T>` with structure: `{code, message, data}`. Error codes are numeric constants in `ErrorCode`.

### Security

- JWT auth with access/refresh token flow.
- All sensitive endpoints require authentication via `JwtAuthenticationFilter`.
- WebSocket connections authenticated via `WebSocketAuthInterceptor`.
- API keys encrypted at rest (AES-256-GCM).
- Ownership checks via `OwnershipCheck` utility — users can only access their own resources.

### Environment

- Dev profile uses hardcoded JWT/encryption secrets in `application-dev.yaml`.
- Test profile uses `application-test.yaml` with its own secrets and minimal exchange config.
- Proxy settings are configured in `.env` for exchange API access (required in dev environment; proxies are disabled in test JVM).
