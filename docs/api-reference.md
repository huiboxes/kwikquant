# REST API Reference

> 自动从 OpenAPI `/v3/api-docs` 生成,**勿手写**。改后端 controller 注解后重跑 `node frontend/scripts/gen-api-reference.mjs`。
> 当前 63 个端点。OpenAPI 原文:运行时 `http://localhost:8080/v3/api-docs`。

所有端点返 `ApiResponse<T>` = `{code, message, data}`,成功 `code=0`;错误码见 [behavior-contract](behavior-contract.md)。

## 目录

- [accounts](#accounts)
- [activity-feed](#activity-feed)
- [ai](#ai)
- [auth](#auth)
- [backtests](#backtests)
- [market](#market)
- [mcp](#mcp)
- [notifications](#notifications)
- [orders](#orders)
- [portfolio](#portfolio)
- [positions](#positions)
- [reports](#reports)
- [risk](#risk)
- [strategies](#strategies)
- [trade-history](#trade-history)

## accounts

### `PUT /api/v1/accounts/{id}`

**更新交易所账户**

需 JWT 鉴权。可更新 label / API key / passphrase，仅可操作本人账户。响应 apiKey 脱敏。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 账户 ID |

请求体: `UpdateAccountRequest`

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` 越权访问他人账户（1002 FORBIDDEN）; `404` 账户不存在（4001 RESOURCE_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `DELETE /api/v1/accounts/{id}`

**删除交易所账户**

需 JWT 鉴权。仅可删除本人账户；越权访问他人账户返回 403（1002）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 账户 ID |

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` 越权访问他人账户（1002 FORBIDDEN）; `404` 账户不存在（4001 RESOURCE_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/accounts`

**查询当前用户交易所账户列表**

需 JWT 鉴权。仅返回当前用户名下账户，apiKey 脱敏。

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/accounts`

**创建交易所账户**

需 JWT 鉴权。API key 端到端加密存储（AES-256-GCM），响应中 apiKey 字段脱敏返回（仅后缀），完整 key 不出后端。label 重复或格式非法返回 400（3001）。实盘（paperTrading=false）必须提供 apiKey/apiSecret，否则返回 400（3001）；exchange 不接受 PAPER。

请求体: `CreateAccountRequest`

响应: `200` OK; `400` 参数非法或 label 重复（3001 VALIDATION_FAILED）; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/accounts/{id}/paper/reset`

**重置模拟盘账户**

需 JWT 鉴权。仅 PAPER 账户:取消活跃订单 + 清持仓 + 余额回 10 万 USDT。非 PAPER 账户返回 400(7001)。仅可操作本人账户。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 账户 ID |

响应: `200` OK; `400` 非 PAPER 账户(7001 VALIDATION_FAILED); `401` ; `403` 越权访问他人账户(1002 FORBIDDEN); `404` 账户不存在(4001 RESOURCE_NOT_FOUND); `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/accounts/{id}/balance`

**查询账户余额**

需 JWT 鉴权。实时拉取交易所余额快照。仅可操作本人账户。交易所不可用返回 502（6001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 账户 ID |

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` 越权访问他人账户（1002 FORBIDDEN）; `404` 账户不存在（4001 RESOURCE_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用（6001 EXCHANGE_UNAVAILABLE）;

## activity-feed

### `GET /api/v1/activity-feed`

**获取活动流**

需 JWT 鉴权。返回当前用户的活动事件列表，按时间倒序。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `limit` | query | 否 | string | 返回条数，默认 10，上限 50 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## ai

### `GET /api/v1/ai/keys`

**查询当前用户 LLM 密钥列表**

需 JWT 鉴权。仅返回元信息 + 末尾 4 位明文，不含完整 key。

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/ai/keys`

**创建 LLM API 密钥**

需 JWT 鉴权。完整 key 加密存储（AES-256-GCM），响应仅返回末尾 4 位明文用于识别展示。OPENAI_COMPATIBLE provider 必须传 baseUrl 与 available_models（≥1）；label 重复返回 400（3001）。

请求体: `CreateLlmKeyRequest`

响应: `200` OK; `400` 参数非法、label 重复或 OPENAI_COMPATIBLE 缺 baseUrl/available_models（; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/ai/keys/{id}/test`

**测试 LLM Key 连通性**

需 JWT 鉴权。后端用该 key + model 发最小 ping(messages=[hi], max_tokens=1, 10s 超时),复用 sanitize 脱敏,不透传 provider 原始错误。key 不存在/非本人返回 404(7001/7004)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 密钥 ID |
| `model` | query | 是 | string | 待测模型名 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` key 不存在或不属于当前用户(7001/7004); `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/ai/chat`

**AI 对话（SSE 流式）**

需 JWT 鉴权。流式响应，返回 Flux<ServerSentEvent>，不套 ApiResponse envelope。pre-stream 阶段（key 校验等）异常由 GlobalExceptionHandler 处理；LLM provider 不支持返回 500（8002），provider 调用错误返回 502（8003）；stream 内异常转为 SSE error event。需先在 LlmApiKeyController 配置 LLM key。会话历史:传入 strategyId 时,controller 层 blocking 保存最后一条 user 消息(role=user)。

请求体: `AiChatRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` LLM provider 未注入/不支持（8002 LLM_KEY_INVALID_PROVIDER）; `502` LLM provider 调用错误（8003 LLM_PROVIDER_ERROR）;

### `DELETE /api/v1/ai/keys/{id}`

**删除 LLM 密钥**

需 JWT 鉴权。仅可删除本人密钥；越权或不存在返回 409（4009）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 密钥 ID |

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` 密钥不存在或不属于当前用户（4009 STATE_CONFLICT）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## auth

### `POST /api/v1/auth/register`

**注册**

公开端点，不需 JWT。创建用户账号，返回 access token + 设置 refresh token cookie。用户名/邮箱已存在返回 400（3001）。

请求体: `RegisterRequest`

响应: `200` OK; `400` 参数非法或用户名/邮箱已存在（3001 VALIDATION_FAILED）; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/auth/refresh`

**刷新 token**

公开端点，不需 JWT。用 refresh_token cookie 换新 access token + 新 refresh token（旋转）。refresh token 缺失/失效返回 401（1001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `refresh_token` | cookie | 否 | string |  |

响应: `200` OK; `400` Bad Request; `401` refresh token 缺失或失效（1001 UNAUTHENTICATED）; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/auth/logout`

**登出**

需 JWT 鉴权。吊销 refresh token + access token 并清除 cookie。refresh token 缺失也视为登出成功（幂等）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `refresh_token` | cookie | 否 | string |  |

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/auth/login`

**登录**

公开端点，不需 JWT。校验凭据，返回 access token（有效期 15min）+ 设置 refresh token cookie（有效期 7d）。凭据无效或账户禁用返回 401（1001）。

请求体: `LoginRequest`

响应: `200` OK; `400` Bad Request; `401` 凭据无效或账户禁用（1001 UNAUTHENTICATED）; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/auth/change-password`

**修改密码**

需 JWT 鉴权。校验旧密码后设置新密码。旧密码错误返回 401（1001）；账户状态冲突返回 409（4009）。

请求体: `ChangePasswordRequest`

响应: `200` OK; `400` Bad Request; `401` 旧密码错误（1001 UNAUTHENTICATED）; `403` Forbidden; `404` Not Found; `409` 账户状态冲突（4009 STATE_CONFLICT，如账户处于不可改密状态）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## backtests

### `GET /api/v1/backtests`

**查询回测任务列表**

需 JWT 鉴权。strategyId 可选:不传返回当前用户全部回测(带 totalReturn + strategyName,供回测 tab 列表 rail);传则按策略过滤其回测历史(不带 totalReturn/strategyName,既有行为)。策略不存在返回 404(7001)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | query | 否 | string | 策略 ID,不传则返回当前用户全部回测 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/backtests`

**提交回测任务**

需 JWT 鉴权。异步提交，立即返回 PENDING 状态的 task（含 taskId）。策略不存在返回 404（7001）；无发布代码返回 409（7006）。前端用 taskId 轮询 GET /backtests/{id}，状态见 behavior-contract §3。

请求体: `SubmitBacktestRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 策略无发布代码（7006 STRATEGY_NO_PUBLISHED_CODE）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/backtests/{taskId}/progress`

**回测进度上报(Worker 通道)**

Worker(X-Worker-Token 鉴权)逐 bar 上报 processedBars/totalBars。Java 写 backtest_tasks + 发 WS RUNNING 增量(前端进度条)。task 非 RUNNING 静默跳过(已终态不误推进度)。返 204,Worker 不消费 body。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `taskId` | path | 是 | string | 回测任务 ID |

请求体: `BacktestProgressRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/backtests/{taskId}/orders`

**回测下单**

Worker 通道（X-Worker-Token 鉴权，filter 内直写 401/7301，不经 advice）。仅回测模式，account 为 pseudo。逐 bar 提交订单 + 快照，撮合返回 Fill；无成交（maker 未成交）返回 204。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `taskId` | path | 是 | string | 回测任务 ID |

请求体: `BacktestOrderRequest`

响应: `200` OK; `400` 回测下单被拒（7302 BACKTEST_ORDER_REJECTED）; `401` ; `403` Forbidden; `404` Not Found; `409` 回测任务未运行（7303 BACKTEST_TASK_NOT_RUNNING）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/backtests/{taskId}/klines`

**回测拉历史 K 线(Worker 通道)**

Worker 通道(X-Worker-Token 鉴权)。走 fetchKlineRangeApiFirst(API-first + Caffeine 缓存,不查 klines 表)。区间空 → 返空 list(worker 据此 exit 2 → Java markFailed 7304)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `taskId` | path | 是 | string | 回测任务 ID |
| `exchange` | query | 是 | string | 交易所 |
| `marketType` | query | 是 | string | 市场类型 |
| `symbol` | query | 是 | string | canonical symbol,如 BTC/USDT |
| `interval` | query | 是 | string | K 线周期(1m\|5m\|15m\|1h\|4h\|1d) |
| `start` | query | 是 | string | 区间起点(含,ISO-8601) |
| `end` | query | 是 | string | 区间终点(不含,ISO-8601) |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用(6001 EXCHANGE_UNAVAILABLE);

### `GET /api/v1/backtests/{id}`

**查回测任务**

需 JWT 鉴权。用于轮询任务状态。任务不存在或非本人返回 404（7100）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 任务 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 回测任务不存在或不属于当前用户（7100 BACKTEST_TASK_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## market

### `POST /api/v1/market/unsubscribe`

**退订行情**

取消订阅指定交易对的 ticker 推送。需 JWT 鉴权。

请求体: `SubscribeRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/market/unsubscribe/kline`

**退订 K 线**

按 interval 退订指定交易对的 kline 推送(不影响同 symbol 的 ticker)。需 JWT 鉴权。

请求体: `KlineSubscribeRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/market/subscribe`

**订阅行情**

订阅指定交易对的实时 ticker 推送（WS）。需 JWT 鉴权。

请求体: `SubscribeRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用（6001 EXCHANGE_UNAVAILABLE）;

### `POST /api/v1/market/subscribe/kline`

**订阅 K 线**

按 interval 订阅指定交易对的实时 K 线推送(WS /topic/kline/...)。需 JWT 鉴权。前端切 interval 时调,后端按需起 kline worker,idle 30s 自动退订。

请求体: `KlineSubscribeRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用（6001 EXCHANGE_UNAVAILABLE）;

### `GET /api/v1/market/tickers`

**批量查行情(可排序分页)**

按交易所 + 市场类型返回全量 active symbol 的批量行情快照(1 次 fetchTickers 替 N 次 fetchTicker)。sort 支持 quoteVolume(默认,成交额)/percentage(涨跌幅)/last(最新价),order desc(默认)/asc,limit 默认 200 上限 500,search 按 canonical symbol like 过滤。stale 全 false(快照语义,非 worker 实时性;10s Caffeine 缓存)。需 JWT 鉴权。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `exchange` | query | 是 | string | 交易所 |
| `marketType` | query | 是 | string | 市场类型 |
| `sort` | query | 否 | string | 排序字段:quoteVolume(默认)/percentage/last |
| `order` | query | 否 | string | 排序方向:desc(默认)/asc |
| `limit` | query | 否 | string | 返回数量,1-500,默认 200 |
| `search` | query | 否 | string | canonical symbol 搜索(like,如 BTC) |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用(6001 EXCHANGE_UNAVAILABLE);

### `GET /api/v1/market/ticker/{exchange}/{marketType}/{symbol}`

**查最新行情**

返回最新 ticker + stale 状态。persistent symbol 走 worker 内存/DB(staleThreshold 5s 判 fresh);非 persistent symbol 无 worker 持续推 → CCXT fetchTicker 拉单次快照,stale=true(非实时)。URL 中 symbol 用 "-" 替代 "/"：BTC-USDT → BTC/USDT。需 JWT 鉴权。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `exchange` | path | 是 | string | 交易所 |
| `marketType` | path | 是 | string | 市场类型 |
| `symbol` | path | 是 | string | symbol，URL 中用-替代/，如 BTC-USDT |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用(6001 EXCHANGE_UNAVAILABLE)——非 persistent symbol fallb;

### `GET /api/v1/market/pairs`

**查询交易对列表**

按交易所 + 市场类型返回可交易对。需 JWT 鉴权。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `exchange` | query | 是 | string | 交易所（枚举: BINANCE \| OKX \| BITGET \| PAPER） |
| `marketType` | query | 是 | string | 市场类型（枚举: SPOT \| PERP） |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用（6001 EXCHANGE_UNAVAILABLE）;

### `GET /api/v1/market/orderbook/{exchange}/{marketType}/{symbol}`

**查盘口深度**

返回指定交易对的买卖盘口。URL 中 symbol 用 "-" 替代 "/"：BTC-USDT → BTC/USDT。需 JWT 鉴权。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `exchange` | path | 是 | string | 交易所 |
| `marketType` | path | 是 | string | 市场类型 |
| `symbol` | path | 是 | string | symbol，URL 中用-替代/，如 BTC-USDT |
| `depth` | query | 否 | string | 深度档数，1-100，默认 20 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用（6001 EXCHANGE_UNAVAILABLE）;

### `GET /api/v1/market/klines`

**查历史 K 线**

按交易所/市场/symbol/interval 返回历史 K 线。需 JWT 鉴权。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `exchange` | query | 是 | string | 交易所 |
| `marketType` | query | 是 | string | 市场类型 |
| `symbol` | query | 是 | string | canonical symbol，如 BTC/USDT |
| `interval` | query | 是 | string | K 线周期（枚举: 1m\|5m\|15m\|1h\|4h\|1d 等） |
| `limit` | query | 否 | string | 返回条数，1-1000，默认 100 |
| `before` | query | 否 | string | 往前加载历史:返回 open_time < before 的最近 N 根(ISO-8601,如 2026-07-17T10:00:00Z)。省略=最近 N 根 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` 交易所不可用（6001 EXCHANGE_UNAVAILABLE）;

## mcp

### `GET /api/v1/mcp/tokens`

**查询当前用户 PAT 列表**

需 JWT 鉴权。仅返回 token 元信息（name/创建时间/状态），不含明文 token。

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/mcp/tokens`

**创建 MCP PAT**

需 JWT 鉴权。创建 Personal Access Token，**明文 token 仅在此响应中返回一次，后续列表不再返回，请即保存**。同名 token 重复返回 400（3001）。

请求体: `CreateMcpTokenRequest`

响应: `200` OK; `400` token 名重复或格式非法（3001 VALIDATION_FAILED）; `401` Unauthorized; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `DELETE /api/v1/mcp/tokens/{id}`

**吊销 MCP PAT**

需 JWT 鉴权。仅可吊销本人 token；越权返回 403（1002），token 不存在返回 404（4001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | token ID |

响应: `200` OK; `400` Bad Request; `401` Unauthorized; `403` 越权吊销他人 token（1002 FORBIDDEN）; `404` token 不存在（4001 RESOURCE_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## notifications

### `GET /api/v1/notifications/preferences`

**查询通知偏好**

返回当前用户全部通知偏好（user 维度，非 account）。需 JWT 鉴权。

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `PUT /api/v1/notifications/preferences`

**批量更新通知偏好**

幂等 upsert，返回更新后的偏好列表。需 JWT 鉴权。

请求体: `NotificationPreferenceRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## orders

### `GET /api/v1/orders`

**分页查询订单**

需 JWT 鉴权。按账户 + 可选 symbol/status/时间范围过滤。accountId 鉴权校验归属，越权返回 403（1002）。日期格式非法或 status 枚举非法返回 400（4103）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `query` | query | 是 | OrderListQuery |  |

响应: `200` OK; `400` 参数非法（4103 ORDER_INVALID_PARAMS：日期格式/status 枚举非法）; `401` ; `403` 越权访问他人账户（1002 FORBIDDEN）; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/orders`

**提交订单**

双通道鉴权——用户请求：JWT + body.accountId 必填（后端校验账户归属）；Worker 请求：X-Worker-Token + body.accountId 应为空（后端据 token 推导）。风控拒绝时 HTTP 200 + code=4105（业务结果，非错误）。

请求体: `OrderSubmitRequest`

响应: `200` 风控拒绝（code=4105 ORDER_RISK_REJECTED，HTTP 200 是业务结果非错误）; `201` Created; `400` 订单参数非法（4103 ORDER_INVALID_PARAMS）; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` 订单状态不可转移（4101）或余额不足（4102）; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/orders/{orderId}`

**查订单详情**

需 JWT 鉴权。订单不存在返回 404（4001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `orderId` | path | 是 | integer |  |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 订单不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `DELETE /api/v1/orders/{orderId}`

**撤单**

需 JWT 鉴权。返回 202 ACCEPTED + OrderCancelResult。订单已成交/不可撤返回 422（4101）；并发版本冲突返回 409（4107）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `orderId` | path | 是 | integer |  |

响应: `200` OK; `202` Accepted; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` 并发版本冲突（4107 ORDER_CONCURRENCY_CONFLICT）; `422` 订单状态不可撤，如已 FILLED（4101 ORDER_ILLEGAL_STATE_TRANSITION）; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/orders/{orderId}/fills`

**查成交记录**

需 JWT 鉴权。按 orderId 返回成交明细列表，含 taker/maker 标识。订单不存在返回 404（4001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `orderId` | path | 是 | integer |  |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 订单不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## portfolio

### `GET /api/v1/portfolio/summary`

**组合总览**

聚合当前用户多账户余额，按 USDT 估值返回。需 JWT 鉴权。mode=PAPER 仅模拟盘, mode=LIVE 仅实盘, 省略则仅实盘(向后兼容)。部分账户余额拉取失败时返回成功账户子集（降级）；全部账户失败时返回 502（6001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `mode` | query | 否 | string | 账户模式: PAPER / LIVE |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/portfolio/pnl`

**持仓未实现盈亏**

聚合当前用户多账户持仓的未实现盈亏。需 JWT 鉴权。mode=PAPER 仅模拟盘, mode=LIVE 仅实盘, 省略则仅实盘(向后兼容)。余额拉取降级语义同 /summary；全部账户失败时返回 502（6001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `mode` | query | 否 | string | 账户模式: PAPER / LIVE |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/portfolio/equity-curve`

**组合权益曲线**

返回指定天数内的组合权益时间序列。需 JWT 鉴权。mode=PAPER 仅模拟盘, mode=LIVE 仅实盘, 省略则仅实盘(向后兼容)。当前为降级版本，返回基于实时 PnL 快照的单点数据；后续版本将补充定时采集的完整时间序列。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `days` | query | 否 | string | 查询天数，默认 7 |
| `mode` | query | 否 | string | 账户模式: PAPER / LIVE |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## positions

### `POST /api/v1/positions/{positionId}/close`

**平仓**

以反向市价单平掉指定持仓的全部数量。需 JWT 鉴权，校验账户归属。FLAT 或不存在的持仓返回 404（4001）。走完整下单链路（风控+余额冻结+路由）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `positionId` | path | 是 | string | 持仓 ID |

响应: `200` OK; `202` Accepted; `400` Bad Request; `401` ; `403` 越权访问他人账户（1002 FORBIDDEN）; `404` 持仓不存在或已平（4001 RESOURCE_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/positions`

**查询持仓**

需 JWT 鉴权。按账户 + 可选 symbol 返回持仓列表，含未实现盈亏和当前市价。后端校验账户归属，越权返回 403（1002）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `accountId` | query | 否 | string | 账户 ID，鉴权校验归属（Worker 请求应为空，后端据 X-Worker-Token 推导） |
| `symbol` | query | 否 | string | 按 canonical symbol 过滤，为空则返回该账户全部持仓 |

响应: `200` OK; `400` Bad Request; `401` ; `403` 越权访问他人账户（1002 FORBIDDEN）; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## reports

### `GET /api/v1/reports`

**分页查询回测报告**

按当前用户鉴权过滤，可选 symbol 过滤。结果按创建时间倒序。需 JWT 鉴权。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `page` | query | 否 | string | 页码，1-based，默认 1 |
| `pageSize` | query | 否 | string | 每页条数，1-100，默认 20 |
| `symbol` | query | 否 | string | 按 canonical symbol 过滤，如 BTC/USDT；为空则不过滤 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/reports`

**提交回测报告**

用户提交回测结果（含交易明细 + 权益曲线）生成报告。需 JWT 鉴权。服务端校验交易数量/权益点数上限、价格/数量正值、时间区间合法性，非法时返回 9002。

请求体: `BacktestSubmitRequest`

响应: `200` OK; `201` Created; `400` 报告载荷非法（9002 REPORT_INVALID_PAYLOAD：交易为空/超限、价格数量非正、区间非法等）; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/reports/import`

**导入回测结果**

与 /reports 类似，但 source 标记为 IMPORT，用于外部回测结果入库。需 JWT 鉴权。服务端校验同 submit，非法时返回 9002。

请求体: `BacktestSubmitRequest`

响应: `200` OK; `201` Created; `400` 报告载荷非法（9002 REPORT_INVALID_PAYLOAD：交易为空/超限、价格数量非正、区间非法等）; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/reports/compare`

**多策略报告对比**

传入 ≥2 个 reportId，返回报告列表 + 按指标的排名。鉴权校验每个报告归属。需 JWT 鉴权。

请求体: `CompareRequest`

响应: `200` OK; `400` 对比报告数不足/超限（9002 REPORT_INVALID_PAYLOAD：<2 或 >20 个 reportId）; `401` ; `403` Forbidden; `404` 可访问的报告不足以对比（9001 REPORT_NOT_FOUND：reportId 不存在或不属于当前用户）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/reports/{id}`

**查询报告详情**

含指标、交易明细、权益曲线。鉴权校验报告归属。需 JWT 鉴权。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 报告 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 报告不存在或不属于当前用户（9001 REPORT_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## risk

### `PUT /api/v1/risk/policies/{policyId}`

**更新风控策略**

需 JWT 鉴权。可更新 name + params。策略不存在或非本人返回 409（4009）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `policyId` | path | 是 | string | 策略 ID |

请求体: `RiskPolicyRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（2010 RISK_POLICY_NOT_FOUND）; `409` 策略状态冲突，不存在或非本人（4009 STATE_CONFLICT）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `DELETE /api/v1/risk/policies/{policyId}`

**删除风控策略**

需 JWT 鉴权。返回 204 NO_CONTENT。策略不存在或非本人返回 409（4009）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `policyId` | path | 是 | string | 策略 ID |

响应: `200` OK; `204` No Content; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（2010 RISK_POLICY_NOT_FOUND）; `409` 策略状态冲突，不存在或非本人（4009 STATE_CONFLICT）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/risk/policies`

**查询风控策略列表**

需 JWT 鉴权。accountId 省略时跨账户返当前用户所有账户策略(风控页总览用);非空则按账户过滤并校验归属(越权返 403 1002)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `accountId` | query | 否 | string | 账户 ID,可省略(省略跨账户查当前用户全部) |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/risk/policies`

**创建风控策略**

需 JWT 鉴权。同一账户同 ruleType 的策略 scope 不可重叠，重叠返回 409（2011）。ruleType/params 非法返回 400（3001）。

请求体: `RiskPolicyRequest`

响应: `200` OK; `201` Created; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` 策略冲突，同账户同 ruleType scope 重叠（2011 RISK_POLICY_CONFLICT）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/risk/dry-run`

**风控预检（不下单）**

需 JWT 鉴权。用与真实下单相同的计算路径评估风控 verdict，不落订单、不冻结余额、不写 RiskDecision、不发事件。越权访问他人账户返回 404（防探测，不返回 403）。

请求体: `RiskDryRunRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 账户不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND，越权也返 404 防探测）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `PATCH /api/v1/risk/policies/{policyId}/toggle`

**启停风控策略**

需 JWT 鉴权。false 表示策略存在但不生效。策略不存在或非本人返回 409（4009）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `policyId` | path | 是 | string | 策略 ID |

请求体: `ToggleRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（2010 RISK_POLICY_NOT_FOUND）; `409` 策略状态冲突，不存在或非本人（4009 STATE_CONFLICT）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/risk/decisions`

**分页查询风控决策**

需 JWT 鉴权。accountId 省略时跨账户返当前用户所有账户决策(风控页总览用);非空则按账户过滤(越权返 403 1002)。可选 verdict/时间范围。verdict=REJECTED 的决策 data 字段含 2001 RISK_REJECTED 业务码(非 HTTP 响应码)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `orderId` | query | 是 | string | 订单 ID |
| `accountId` | query | 否 | string | 账户 ID,可省略(省略跨账户查当前用户全部) |
| `verdict` | query | 否 | string | 按 verdict 过滤(枚举: APPROVED \| REJECTED) |
| `startTime` | query | 否 | string | created_at 下限 ISO-8601 |
| `endTime` | query | 否 | string | created_at 上限 ISO-8601 |
| `page` | query | 否 | string | 页码,1-based,默认 1 |
| `pageSize` | query | 否 | string | 每页条数,默认 50,最大 200 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 决策不存在或不属于当前用户（4001 RESOURCE_NOT_FOUND，越权也返 404 防探测）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## strategies

### `GET /api/v1/strategies/{strategyId}/codes/{codeId}`

**查代码版本详情**

需 JWT 鉴权。返回含 sourceCode 正文（list 端点不含 sourceCode，前端 Monaco reload 草稿走此端点，契约改动 A）。代码不存在/非本人返回 404（7004）；策略不存在返回 404（7001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |
| `codeId` | path | 是 | string | 代码版本 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略或代码不存在（7001 STRATEGY_NOT_FOUND / 7004 STRATEGY_CODE_NOT_FO; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `PUT /api/v1/strategies/{strategyId}/codes/{codeId}`

**更新代码草稿**

需 JWT 鉴权。仅 DRAFT 状态可改；发布后冻结，新版本走新 codeId。代码不存在返回 404（7004）；非 DRAFT 返回 409（7005）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |
| `codeId` | path | 是 | string | 代码版本 ID |

请求体: `UpdateCodeRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略或代码不存在（7001/7004）; `409` 代码非 DRAFT 不可改（7005）或不存在/非本人（4009）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `DELETE /api/v1/strategies/{strategyId}/codes/{codeId}`

**删除代码草稿**

需 JWT 鉴权。仅 DRAFT 可删(放弃当前未发布草稿);PUBLISHED/ARCHIVED 不可删返 409(7005)。代码不存在返 404(7004)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |
| `codeId` | path | 是 | string | 代码版本 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略或代码不存在（7001/7004）; `409` 代码非 DRAFT 不可删（7005）或不存在/非本人（4009）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/strategies/{id}`

**查策略详情**

需 JWT 鉴权。策略不存在或非本人返回 404（7001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在或不属于当前用户（7001 STRATEGY_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `PUT /api/v1/strategies/{id}`

**更新策略**

需 JWT 鉴权。仅 DRAFT/STOPPED 状态可改;状态不可编辑返回 409(7007),不存在或非本人返回 409(4009)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

请求体: `UpdateStrategyRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 状态不可编辑（7007 STRATEGY_NOT_EDITABLE）或策略不存在/非本人（4009 STATE_CONF; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `DELETE /api/v1/strategies/{id}`

**删除策略**

需 JWT 鉴权。DRAFT/READY/STOPPED 可删(无活跃 worker);RUNNING/PAUSED/ERROR 需先停止,不可删返回 409(7007)。不存在/非本人返回 409(4009)。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` 状态不可删除（7007 STRATEGY_NOT_EDITABLE,需先停止）或策略不存在/非本人（4009 STATE; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/strategies`

**查询当前用户策略列表**

需 JWT 鉴权。仅返回当前用户名下策略。

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/strategies`

**创建策略**

需 JWT 鉴权。创建处于 DRAFT 状态的策略。参数非法返回 400（3001）。

请求体: `CreateStrategyRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/strategies/{strategyId}/codes`

**查询策略代码版本列表**

需 JWT 鉴权。按版本号倒序返回，不含 sourceCode 正文。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/strategies/{strategyId}/codes`

**创建代码草稿**

需 JWT 鉴权。为策略创建 DRAFT 状态的代码版本。已有未发布 DRAFT 返回 409（7005）；sourceCode 超 1MB 返回 400（3001）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |

请求体: `CreateCodeRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 已有未发布 DRAFT，不可重复创建（7005 STRATEGY_CODE_ILLEGAL_STATE）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/strategies/{strategyId}/codes/{codeId}/publish`

**发布代码版本**

需 JWT 鉴权。DRAFT→PUBLISHED 转移，发布后冻结不可改，新版本走新 codeId。代码不存在返回 404（7004）；非 DRAFT 返回 409（7005）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |
| `codeId` | path | 是 | string | 代码版本 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略或代码不存在（7001/7004）; `409` 代码非 DRAFT 不可发布（7005）或不存在/非本人（4009）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/strategies/{strategyId}/ai/messages`

**查询策略 AI 会话历史**

需 JWT 鉴权。按 created_at 升序返回,limit 200 防爆。策略不存在返回 404(7001);非本人策略返回 403。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` 非本人策略（1002 FORBIDDEN）; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/strategies/{strategyId}/ai/messages`

**保存 AI 回复消息**

需 JWT 鉴权。前端 SSE onClose 时调用,保存完整 AI 回复文本 + 本次用的 model。策略不存在返回 404(7001);非本人策略返回 403。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |

请求体: `SaveAiMessageRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` 非本人策略（1002 FORBIDDEN）; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `DELETE /api/v1/strategies/{strategyId}/ai/messages`

**清空策略 AI 会话历史**

需 JWT 鉴权。策略不存在返回 404(7001);非本人策略返回 403。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `strategyId` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` 非本人策略（1002 FORBIDDEN）; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/strategies/{id}/stop`

**停止策略**

需 JWT 鉴权。RUNNING/PAUSED/ERROR→STOPPED 转移。状态不可转移返回 409（7002）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 状态不可转移（7002/4009）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/strategies/{id}/start`

**启动策略**

需 JWT 鉴权。READY|PAUSED|ERROR→RUNNING 转移（PAUSED→RUNNING 即 resume，ERROR→RUNNING 即重试，复用同一端点，无独立 resume 端点），需有发布代码。无发布代码返回 409（7006）；状态不可转移返回 409（7002）；Worker 启动失败返回 500（7200）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

请求体: `StartRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 无发布代码（7006）或状态不可转移（7002/4009）; `422` Unprocessable Content; `500` Worker 启动失败（7200 WORKER_START_FAILED）; `502` Bad Gateway;

### `POST /api/v1/strategies/{id}/restart`

**重新启动策略**

需 JWT 鉴权。STOPPED→RUNNING 转移（用已发布代码恢复运行，可切账户）。无发布代码返回 409（7006）；状态不可转移返回 409（7002）；Worker 启动失败返回 500（7200）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

请求体: `StartRequest`

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 无发布代码（7006）或状态不可转移（7002/4009）; `422` Unprocessable Content; `500` Worker 启动失败（7200 WORKER_START_FAILED）; `502` Bad Gateway;

### `POST /api/v1/strategies/{id}/ready`

**标记策略就绪**

需 JWT 鉴权。DRAFT→READY 转移。无发布代码返回 409（7006）；状态不可转移返回 409（7002）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 无发布代码（7006）或状态不可转移（7002/4009）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `POST /api/v1/strategies/{id}/pause`

**暂停策略**

需 JWT 鉴权。RUNNING→PAUSED 转移。状态不可转移返回 409（7002）。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `id` | path | 是 | string | 策略 ID |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` 策略不存在（7001 STRATEGY_NOT_FOUND）; `409` 状态不可转移（7002/4009）; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/strategies/last-edited`

**获取最近编辑的策略**

需 JWT 鉴权。返回当前用户最近编辑的策略，无策略时 data 为 null。

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

## trade-history

### `GET /api/v1/trade-history`

**分页查询交易历史**

聚合多账户订单 + 成交，按订单维度返回。需 JWT 鉴权。accountId 为空表示查当前用户全部账户。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `accountId` | query | 否 | string | 账户 ID，为空则查全部账户 |
| `symbol` | query | 否 | string | 按 canonical symbol 过滤，如 BTC/USDT |
| `startTime` | query | 否 | string | 起始时间 ISO-8601，为空则不限 |
| `endTime` | query | 否 | string | 结束时间 ISO-8601，为空则不限 |
| `page` | query | 否 | string | 页码，1-based，默认 1 |
| `pageSize` | query | 否 | string | 每页条数，1-100，默认 20 |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/trade-history/stats`

**交易统计**

按账户/时间范围聚合成交额、累计手续费、已实现盈亏。需 JWT 鉴权。accountId 为空表示全部账户。mode=PAPER/LIVE 过滤账户类型。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `accountId` | query | 否 | string | 账户 ID，为空则全部账户 |
| `since` | query | 否 | string | 统计起始时间 ISO-8601，为空则不限 |
| `mode` | query | 否 | string | 账户模式: PAPER / LIVE |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` Internal Server Error; `502` Bad Gateway;

### `GET /api/v1/trade-history/export`

**导出交易历史**

按条件导出 CSV/JSON 文件。需 JWT 鉴权。导出失败返回 9004。

| 参数 | 位置 | 必填 | 类型 | 说明 |
|---|---|---|---|---|
| `accountId` | query | 否 | string | 账户 ID，为空则全部账户 |
| `symbol` | query | 否 | string | 按 canonical symbol 过滤 |
| `startTime` | query | 否 | string | 起始时间 ISO-8601 |
| `endTime` | query | 否 | string | 结束时间 ISO-8601 |
| `format` | query | 否 | string | 导出格式（枚举: csv \| json），默认 csv |

响应: `200` OK; `400` Bad Request; `401` ; `403` Forbidden; `404` Not Found; `409` Conflict; `422` Unprocessable Content; `500` 导出失败（9004 REPORT_EXPORT_FAILED：序列化或 IO 异常）; `502` Bad Gateway;

