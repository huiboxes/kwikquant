# KwikQuant WebSocket 主题契约(Wave 8 契约冻结)

> 版本:0.0.1 (Wave 8)。**本文档为前端(Dashboard)与外部 SDK 消费者共同引用的 WS 主题合约冻结版**。
> 补 OpenAPI(`/v3/api-docs`)对 REST 的覆盖 — OpenAPI 3.0 不擅长表达 WebSocket 主题订阅语义(AsyncAPI 更适合,但引入代价过高),因此 WS 契约用本 markdown 单独维护,自动化契约测试见 `src/test/java/com/kwikquant/e2e/`。

## 1. 传输 & 鉴权

- **协议**:STOMP over WebSocket(Spring `simpMessagingTemplate` 推,`WebSocketAuthInterceptor` 验)。
- **入口 URL**:`ws(s)://<host>/ws-native`(客户端与 Spring 的 STOMP endpoint 一致)。
- **鉴权**:CONNECT 帧携带**下列两种 header 之一**(`WebSocketAuthInterceptor` 优先 `X-Worker-Token`,失败**不 fallback** 到 JWT;防混用攻击):
  - **JWT**(外部用户/前端):`Authorization: Bearer <jwt>`;`JwtProvider.parseToken` 验证,得 `userId`。
  - **service token**(Worker):`X-Worker-Token: <uuid>`(**与 REST 侧一致**,不走 `Authorization: Bearer`);`WorkerTokenService.getEntry` 验证,得 `strategyId` + `userId` + `exchange`。
- **多路复用**:同一 STOMP 连接可 SUBSCRIBE 多个 `/topic/...`;handler 按 destination 派发。

## 2. Topic 总览

| Topic | 推送方(Java 模块) | 消息 schema | 订阅方 |
|---|---|---|---|
| `/topic/ticks/{exchange}/{marketType}/{symbol}` | market | `TickEvent` | Worker + Dashboard |
| `/topic/klines/{exchange}/{marketType}/{symbol}/{interval}` | market | `KlineEvent` | Dashboard |
| `/topic/orders/{userId}` | trading | `OrderEvent` | Worker + Dashboard |
| `/topic/fills/{userId}` | trading | `FillEvent`(镜像 `Fill`) | Worker + Dashboard |
| `/topic/positions/{userId}` | trading | `PositionEvent` | Dashboard |
| `/topic/backtests/{userId}` | strategy | `BacktestEvent` | Dashboard |
| `/topic/notifications/{userId}` | notification | `NotificationEvent` | Dashboard |
| `/topic/portfolio/{userId}` | report | `PortfolioEvent` | Dashboard |
| `/topic/risk/{userId}` | risk | `RiskEvent` | Dashboard(通过 notification 通道) |

## 3. Message Schemas(JSON)

### 3.1 TickEvent

```json
{
  "exchange": "BINANCE",         // Exchange 枚举字符串
  "marketType": "SPOT",           // SPOT | FUTURES
  "symbol": "BTC/USDT",
  "bid": "42150.00",              // BigDecimal 字符串
  "ask": "42151.00",
  "last": "42150.50",
  "timestamp": "2024-01-15T08:00:00Z"
}
```

### 3.2 KlineEvent

```json
{
  "exchange": "BINANCE",
  "marketType": "SPOT",
  "symbol": "BTC/USDT",
  "interval": "1h",
  "openTime": "2024-01-15T08:00:00Z",
  "open": "42100",
  "high": "42200",
  "low": "42050",
  "close": "42150",
  "volume": "123.4"
}
```

### 3.3 OrderEvent

推送时机:订单状态变更(NEW → FILLED/CANCELLED/REJECTED)。

```json
{
  "orderId": 42,
  "status": "FILLED",           // NEW | PARTIAL | FILLED | CANCELLED | REJECTED
  "symbol": "BTC/USDT",
  "side": "BUY",
  "orderType": "MARKET",
  "amount": "0.1",
  "price": null,               // LIMIT 有价,MARKET null
  "filledQty": "0.1",
  "avgFillPrice": "42150",
  "updatedAt": "2024-01-15T08:00:01Z"
}
```

### 3.4 FillEvent

镜像 `trading.domain.Fill`。回测的 fill **不推此主题**(回测的 fill 由 Worker 从 HTTP response 直接取)。

```json
{
  "orderId": 42,
  "accountId": 7,               // 回测下为 0(pseudo account)
  "symbol": "BTC/USDT",
  "side": "BUY",
  "price": "42150",
  "qty": "0.1",
  "fee": "0.4215",
  "feeCurrency": "USDT",
  "liquidity": "taker",         // taker | maker
  "externalFillId": "abc-uuid",
  "filledAt": "2024-01-15T08:00:01Z"
}
```

### 3.5 PositionEvent

```json
{
  "symbol": "BTC/USDT",
  "qty": "0.1",                 // 正=多,负=空,0=平
  "avgPrice": "42150",
  "unrealizedPnl": "5.0",
  "realizedPnl": "0.0",
  "updatedAt": "2024-01-15T08:00:01Z"
}
```

### 3.6 BacktestEvent

回测任务状态变更(PENDING → RUNNING → COMPLETED/FAILED)。

```json
{
  "taskId": 12,
  "status": "COMPLETED",         // RUNNING | COMPLETED | FAILED
  "error": null,                // FAILED 才有值,COMPLETED/RUNNING null
  "timestamp": "2024-01-15T08:00:01Z"
}
```

### 3.7 NotificationEvent

```json
{
  "id": 100,
  "type": "RISK_TRIGGERED",      // RISK_TRIGGERED | STRATEGY_STARTED | STRATEGY_STOPPED | STRATEGY_ERROR | ...
  "title": "Order rejected by risk",
  "body": "MAX_NOTIONAL exceeded (10000 > 5000)",
  "timestamp": "2024-01-15T08:00:01Z"
}
```

### 3.8 PortfolioEvent

```json
{
  "totalEquity": "10500",       // BigDecimal 字符串
  "cash": "5000",
  "positionValue": "5500",
  "unrealizedPnl": "500",
  "realizedPnl": "0",
  "timestamp": "2024-01-15T08:00:01Z"
}
```

## 4. 主题聚合关系

```
market → ticks → Worker.on_tick / Dashboard.chart
market → klines → Dashboard.chart(实时替换 K 线尾根)
trading → orders → Worker.on_order / Dashboard.orderList
trading → fills → Worker.on_fill(Runner) / Dashboard.tradeHistory
trading → positions → Dashboard.positionPanel
strategy → backtests → Dashboard.backtestConsole
notification → notifications → Dashboard.toast
report → portfolio → Dashboard.dashboard(总览)
```

## 5. Worker 订阅

- 模拟盘/实盘 Runner(`kwikquant_worker.event_loop.RunnerEventLoop`)订阅:
  - `/topic/ticks/{exchange}/{marketType}/{symbol}` — 触发 `strategy.on_tick`
  - `/topic/fills/{userId}` — 触发 `strategy.on_fill`
  - `/topic/orders/{userId}` — 可选,策略跟单场景
- 回测 Worker(`kwikquant_worker.event_loop.BacktestEventLoop`)**不订阅 WS**:回测 fill 走 HTTP response 同步返回。

## 6. 版本约定

- 契约变更遵循 semver;向后兼容的字段添加(新字段可 null)按 minor;删字段/改字段类型按 major。
- 前端生成 TypeScript 类型:`openapi-typescript` 覆盖 REST;WS 类型从本文档手动镜像到 `dashboard/src/types/ws.ts`。
- 契约测试:`src/test/java/com/kwikquant/e2e/*E2ETest.java`(六链路)验证发送方 schema 与本文档一致。

## 7. 已知 TODO

- [ ] AsyncAPI schema 自动生成(Wave 9+):当 topic 数 > 15 或 schema 复杂化时考虑引入。
- [ ] 心跳与断线重连策略文档化(客户端目前依赖 STOMP heart-beat 帧,默认 10s ping)。
- [ ] Runner Worker 与用户 Dashboard 同订阅 `/topic/fills/{userId}` 时的推送顺序无保证(broker 内 topic 广播 fanout);策略侧应基于消息 timestamp 排序而非到达顺序。
