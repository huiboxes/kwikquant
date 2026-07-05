# KwikQuant WebSocket 主题契约(Wave 8 契约冻结)

> 版本:0.0.1 (Wave 8)。**本文档为前端(Dashboard)与外部 SDK 消费者共同引用的 WS 主题合约冻结版**。
> 补 OpenAPI(`/v3/api-docs`)对 REST 的覆盖 — OpenAPI 3.0 不擅长表达 WebSocket 主题订阅语义(AsyncAPI 更适合,但引入代价过高),因此 WS 契约用本 markdown 单独维护,自动化契约测试见 `src/test/java/com/kwikquant/e2e/`。

## 1. 传输 & 鉴权

- **协议**:STOMP over WebSocket(Spring `simpMessagingTemplate` 推,`WebSocketAuthInterceptor` 验)。
- **入口 URL**:`ws(s)://<host>/ws-native`(客户端与 Spring 的 STOMP endpoint 一致)。
- **鉴权**(契约改动 F,按实际代码 `WebSocketAuthInterceptor.beforeHandshake`):
  - **JWT**(外部用户/前端):**HTTP 握手阶段**带 `refresh_token` cookie(path=`/`,与 REST refresh 端点共用同一 cookie),`WebSocketAuthInterceptor.beforeHandshake`(`HandshakeInterceptor`)校验 JWT + refresh token 白名单(jti 未撤销未过期)。**不走 STOMP CONNECT 帧 Bearer header**(后端不读)。失败 `return false` 拒绝 HTTP 升级(**无 STOMP ERROR 帧**),前端 `webSocket` 连接失败 → 走 §8.2 重连退避。cookie path=`/` 浏览器自动附带,前端无需额外处理。
  - **service token**(Worker):`X-Worker-Token: <uuid>` header;`WorkerTokenService.getEntry` 验证,得 `strategyId` + `userId` + `exchange`。
- **多路复用**:同一 STOMP 连接可 SUBSCRIBE 多个 `/topic/...`;handler 按 destination 派发。

### 1.5 SUBSCRIBE 帧示例

```
SUBSCRIBE
id:sub-0
destination:/topic/ticks/BINANCE/SPOT/BTC/USDT

^@
```

- `id` 客户端自增唯一,用于 UNSUBSCRIBE;`destination` 路径段区分大小写,`symbol` 用 `/`(不转义,与 REST `/ticker/{symbol}` 的 `-` 替换互逆)。
- 鉴权由 CONNECT 帧承担,SUBSCRIBE 帧无需再带 token;`WebSocketAuthInterceptor` 在 CONNECT 时已校验。
- SUBSCRIBE 后 broker 即开始推送(无确认帧);UNSUBSCRIBE 即停。

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

**字段表：**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| exchange | string | 是 | 交易所（枚举: BINANCE \| OKX \| BYBIT \| PAPER） |
| marketType | string | 是 | 市场类型（枚举: SPOT \| FUTURES） |
| symbol | string | 是 | canonical symbol，如 BTC/USDT |
| bid | string | 是 | 买一价（BigDecimal 字符串） |
| ask | string | 是 | 卖一价 |
| last | string | 是 | 最新成交价 |
| timestamp | string | 是 | 行情时间 ISO-8601 UTC |

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

**字段表：**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| exchange | string | 是 | 交易所（枚举: BINANCE \| OKX \| BYBIT \| PAPER） |
| marketType | string | 是 | 市场类型（枚举: SPOT \| FUTURES） |
| symbol | string | 是 | canonical symbol |
| interval | string | 是 | K 线周期（枚举: 1m \| 5m \| 15m \| 1h \| 4h \| 1d 等） |
| openTime | string | 是 | 开盘时间 ISO-8601 UTC |
| open | string | 是 | 开盘价（BigDecimal 字符串） |
| high | string | 是 | 最高价 |
| low | string | 是 | 最低价 |
| close | string | 是 | 收盘价 |
| volume | string | 是 | 成交量 |

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

**字段表：**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| orderId | number | 是 | 订单 ID（int64） |
| status | string | 是 | 订单状态（枚举: NEW \| PARTIAL \| FILLED \| CANCELLED \| REJECTED \| EXPIRED） |
| symbol | string | 是 | canonical symbol |
| side | string | 是 | 方向（枚举: BUY \| SELL） |
| orderType | string | 是 | 订单类型（枚举: LIMIT \| MARKET \| STOP \| STOP_LIMIT） |
| amount | string | 是 | 委托数量（BigDecimal 字符串） |
| price | string \| null | 否 | 限价（LIMIT 有值，MARKET 为 null） |
| filledQty | string | 是 | 已成交数量 |
| avgFillPrice | string | 是 | 成交均价 |
| updatedAt | string | 是 | 最后更新时间 ISO-8601 UTC |

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

**字段表：**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| orderId | number | 是 | 订单 ID |
| accountId | number | 是 | 账户 ID（回测下为 0，pseudo account） |
| symbol | string | 是 | canonical symbol |
| side | string | 是 | 方向（枚举: BUY \| SELL） |
| price | string | 是 | 成交价（BigDecimal 字符串） |
| qty | string | 是 | 成交数量 |
| fee | string | 是 | 手续费 |
| feeCurrency | string | 是 | 手续费币种，如 USDT |
| liquidity | string | 是 | 流动性方向（枚举: taker \| maker） |
| externalFillId | string | 是 | 交易所成交 ID |
| filledAt | string | 是 | 成交时间 ISO-8601 UTC |

> 回测 fill **不推此主题**：回测 fill 由 Worker 从 HTTP response 同步取。

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

**字段表：**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| symbol | string | 是 | canonical symbol |
| qty | string | 是 | 持仓数量（正=多，负=空，0=平；BigDecimal 字符串） |
| avgPrice | string | 是 | 平均开仓价 |
| unrealizedPnl | string | 是 | 未实现盈亏（USDT） |
| realizedPnl | string | 是 | 已实现盈亏（USDT） |
| updatedAt | string | 是 | 最后更新时间 ISO-8601 UTC |

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

**字段表：**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| taskId | number | 是 | 回测任务 ID |
| status | string | 是 | 任务状态（枚举: PENDING \| RUNNING \| COMPLETED \| FAILED） |
| error | string \| null | 否 | 失败原因（仅 FAILED 有值，其余 null） |
| timestamp | string | 是 | 状态变更时间 ISO-8601 UTC |

### 3.7 NotificationEvent

> 契约改动 F:本 schema 与代码不符,已按实际代码(`NotificationEventType.java` + `NotificationService.dispatch`)对齐。`title` 是 `dispatch(userId, title, payload)` 的独立参数,**不入 payload**。

```json
{
  "type": "RISK_REJECTED",
  "orderId": 1,
  "accountId": 1,
  "reason": "exceeds max notional",
  "timestamp": "2026-07-05T12:00:00Z"
}
```

**字段表:**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| type | string | 是 | 事件类型(枚举: `RISK_REJECTED` \| `ORDER_FILLED` \| `ORDER_CANCELLED` \| `STRATEGY_STARTED` \| `STRATEGY_STOPPED` \| `STRATEGY_ERROR`,`NotificationEventType.java`) |
| timestamp | string | 是 | 通知时间 ISO-8601 UTC |
| (其余字段) | varies | 否 | 按 `type` 不同的 payload 字段(如 RISK_REJECTED 带 orderId/accountId/reason),无统一 id/title 字段 |

> RiskEvent 不单独建模:风控触发走 NotificationEvent(type=RISK_REJECTED),通过 notification 通道推送。前端按 `type` switch 渲染 payload。

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

**字段表：**

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| totalEquity | string | 是 | 总权益（USDT 估值，BigDecimal 字符串） |
| cash | string | 是 | 现金余额 |
| positionValue | string | 是 | 持仓市值 |
| unrealizedPnl | string | 是 | 未实现盈亏 |
| realizedPnl | string | 是 | 已实现盈亏 |
| timestamp | string | 是 | 快照时间 ISO-8601 UTC |

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

## 6. 版本约定与推送顺序

- 契约变更遵循 semver;向后兼容的字段添加(新字段可 null)按 minor;删字段/改字段类型按 major。
- 前端生成 TypeScript 类型:`openapi-typescript` 覆盖 REST;WS 类型从本文档手动镜像到 `dashboard/src/types/ws.ts`。
- 契约测试:`src/test/java/com/kwikquant/e2e/*E2ETest.java`(六链路)验证发送方 schema 与本文档一致。
- **推送顺序无保证**:同 userId 多 topic 广播由 broker fanout,**不保证到达顺序**。策略侧/前端按消息 `timestamp` 字段排序而非到达顺序;`FillEvent` 与 `OrderEvent` 可能乱序到达(成交先于订单状态变更),前端用 `orderId` 关联而非时序假设。同 topic 内按发送顺序(broker 单 topic 保序),但跨 topic 无序。

## 7. 决策记录(原 TODO 消化)

> 本节为决策记录而非待办。三项原 TODO 已在 Wave 8 契约冻结时消化,记录决策代价供后续重估。

### 7.1 不引入 AsyncAPI 自动生成(原 TODO ①)

**决策**:WS 契约继续用 markdown + JSON 示例 + 字段表维护,不引入 AsyncAPI 工具链。

**决策代价清单**:
- AsyncAPI spec 需独立工具链(`@asyncapi/parser` + codegen),构建依赖 +1,CI 复杂度上升。
- STOMP 的 AsyncAPI profile 适配不如 HTTP 的 OpenAPI 成熟,自定义 message binding 工作量大。
- 当前 9 topic + 字段表 + E2E 测试已能驱动前端 `ws.ts` 手动镜像;WS 类型字段少(8 schema × 均值 5 字段),手动维护成本 < 引入成本。
- **重估触发条件**:topic > 15 或 schema 复杂化(嵌套 >2 层/枚举 >10 值)时,引入 AsyncAPI 收益超过成本,届时再评估。

### 7.2 心跳与断线重连(原 TODO ②)

**决策**:见 §8 客户端连接管理。STOMP heart-beat 默认 10s ping;客户端断线指数退避重连 + 重订阅。

### 7.3 推送顺序(原 TODO ③)

**决策**:见 §6 注记。broker 不保跨 topic 顺序,消费侧按 `timestamp` + `orderId` 关联,不假设到达顺序。

## 8. 客户端连接管理

### 8.1 心跳

- STOMP heart-beat 帧默认 `10s` ping(C/S 双向);客户端库(`@stomp/stompjs`)配置 `heartbeatIncoming: 10000, heartbeatOutgoing: 10000`。
- 心跳失败(连续 2 个周期无 pong)→ 客户端判定连接断开 → 触发 §8.2 重连。

### 8.2 断线重连

- **指数退避(前端手动)**:1s → 2s → 5s → 10s → 30s(上限),避免雪崩。**库内 `reconnectDelay` 是固定延时非指数退避**,故设 `reconnectDelay: 0` 禁用库内自动重连,全靠 `beforeConnect` 手动计数 + `setTimeout` 实现退避序列(契约改动 F)。
- **重订阅**:重连成功后**重新 SUBSCRIBE 全部主题**(broker 不持久化离线消息,错过的消息不可补;前端通过 REST 拉取最新快照对齐状态)。
- **失败兜底**:连续 5 次重连失败 → 前端 toast 提示"连接异常,请检查网络" + 保留页面状态,用户手动刷新触发重连。

### 8.3 `@stomp/stompjs` 配置样例

```js
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: 'wss://api.kwikquant.com/ws-native',
  // 鉴权:HTTP 握手阶段浏览器自动附带 refresh_token cookie(path=/),非 CONNECT Bearer(契约改动 F)
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
  reconnectDelay: 0,              // 禁用库内自动重连(固定延时非指数退避),beforeConnect 手动 setTimeout 实现退避
  beforeConnect: () => { /* 重连计数 + 退避 1s→2s→5s→10s→30s */ },
  onDisconnect: () => { /* 标记连接断开 */ },
  onStompError: (frame) => { /* 鉴权/协议错误,不重连,跳登录 */ },
  onWebSocketClose: () => { /* 触发手动重连流程 */ },
});

// 重连后重订阅
client.onConnect = () => {
  subscriptions.forEach(({ id, destination }) =>
    client.subscribe(destination, handler, { id })
  );
};
```

### 8.4 服务端主动 DISCONNECT

- JWT 过期/踢线时,服务端发送 ERROR 帧后关闭连接;前端识别 `receipt` 或 `message` 中的错误码(1001 UNAUTHENTICATED)→ 不重连,跳登录页。
- 与 §8.2 的区别:鉴权失效不重连(重连也只会再 401),网络断开才重连。
