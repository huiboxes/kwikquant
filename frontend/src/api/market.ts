import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * market typed client。
 *
 * 端点(均 JWT):
 *  - GET  /api/v1/market/ticker/{exchange}/{marketType}/{symbol} → TickerResponse{ticker, stale}
 *    (symbol URL 中用 - 替 /,如 BTC-USDT;返最新 ticker + stale 二状态)
 *  - GET  /api/v1/market/pairs?exchange=&marketType= → TradingPairInfo[](按交易所+市场类型)
 *  - GET  /api/v1/market/klines?exchange=&marketType=&symbol=&interval=&limit= → Kline[]
 *    (interval 枚举 _1m|_5m|_15m|_1h|_4h|_1d)
 *  - GET   /api/v1/market/orderbook/{exchange}/{marketType}/{symbol}?depth= → OrderBook{bids/asks PriceLevel[]}
 *  - POST /api/v1/market/subscribe → WS 订阅(body SubscribeRequest)
 *  - POST /api/v1/market/unsubscribe → WS 退订
 *
 * honest(记 docs/tech-debt.md TD-008~011):
 *  - 后端无"列表所有 ticker"端点(单 symbol GET),MarketPage hardcode 主流 symbol 循环 GET(TD-008)
 *  - order book 后端有端点(TD-009 已就绪 GET /market/orderbook),TradingPage/MarketPage mock 待替换
 *  - Heatmap 多周期后端无(ticker 单点 percentage),派生 mock(TD-010)
 *  - subscribe/unsubscribe:POST /subscribe 起后端按需 worker + marketStore.subscribeTicker 订 destination 收 WS(已实现,TD-011 落地)
 */
type TickerResponse = components['schemas']['TickerResponse']
type TradingPairInfo = components['schemas']['TradingPairInfo']
type Kline = components['schemas']['Kline']
type SubscribeRequest = components['schemas']['SubscribeRequest']
type OrderBook = components['schemas']['OrderBook']

/** symbol URL 编码:BTC/USDT → BTC-USDT(URL 中 / 用 - 替,契约规定)。 */
function symUrl(symbol: string): string {
  return symbol.replace('/', '-')
}

/** 查最新行情(返 ticker + stale 二状态)。 */
export function fetchTicker(
  exchange: string,
  marketType: string,
  symbol: string,
): Promise<TickerResponse> {
  return apiFetch<TickerResponse>(
    `/api/v1/market/ticker/${exchange}/${marketType}/${symUrl(symbol)}`,
  )
}

/** 查询交易对列表(按交易所+市场类型)。 */
export function fetchPairs(
  exchange: string,
  marketType: string,
): Promise<TradingPairInfo[]> {
  const params = new URLSearchParams({ exchange, marketType })
  return apiFetch<TradingPairInfo[]>(`/api/v1/market/pairs?${params}`)
}

export interface TickersQuery {
  exchange: string
  marketType: string
  /** 排序字段:quoteVolume(默认,成交额)/percentage(涨跌幅)/last(最新价) */
  sort?: 'quoteVolume' | 'percentage' | 'last'
  /** 排序方向:desc(默认)/asc */
  order?: 'asc' | 'desc'
  /** 返回数量,默认 200 上限 500 */
  limit?: number
  /** canonical symbol 搜索(like,如 BTC) */
  search?: string
}

/**
 * 批量查行情(GET /market/tickers,1 次 fetchTickers 替 N 次 fetchTicker)。
 * 返 TickerResponse[](stale 全 false,batch 快照语义;10s 缓存摊薄单请求权重)。
 * sort/order/limit/search 后端应用层做。MarketPage 行情列表用。
 */
export function fetchTickers(q: TickersQuery): Promise<TickerResponse[]> {
  const params = new URLSearchParams({
    exchange: q.exchange,
    marketType: q.marketType,
    sort: q.sort ?? 'quoteVolume',
    order: q.order ?? 'desc',
    limit: String(q.limit ?? 200),
  })
  if (q.search) params.set('search', q.search)
  return apiFetch<TickerResponse[]>(`/api/v1/market/tickers?${params}`)
}

/**
 * 查盘口深度(GET /market/orderbook/{exchange}/{marketType}/{symbol}?depth=)。
 * 返 OrderBook{bids/asks: PriceLevel{price, qty}[], timestamp, receivedAt}。
 * symbol URL 编码同 ticker(/ → -),depth 1-100 默认 20。
 * TD-009 已就绪:替换 TradingPage/MarketPage 硬编码派生 mock。
 */
export function fetchOrderBook(
  exchange: string,
  marketType: string,
  symbol: string,
  depth?: number,
): Promise<OrderBook> {
  const qs = depth ? `?depth=${depth}` : ''
  return apiFetch<OrderBook>(
    `/api/v1/market/orderbook/${exchange}/${marketType}/${symUrl(symbol)}${qs}`,
  )
}

export interface KlinesQuery {
  exchange: string
  marketType: string
  symbol: string
  interval: string // '_1m'|'_5m'|'_15m'|'_1h'|'_4h'|'_1d'
  limit?: number
  /** 往前加载历史:返回 open_time < before 的最近 N 根(ISO-8601,如 2026-07-17T10:00:00Z)。省略=最近 N 根。 */
  before?: string
}

/** 查历史 K 线(按交易所/市场/symbol/interval,limit 控制条数;before 往前加载历史)。 */
export function fetchKlines(q: KlinesQuery): Promise<Kline[]> {
  const params = new URLSearchParams({
    exchange: q.exchange,
    marketType: q.marketType,
    // symbol 直接传 canonical "BTC/USDT":klines 是 @RequestParam(controller 无 - → / 还原),
    // 不是 @PathVariable(ticker/orderbook 才需 symUrl 替 -,别混)。
    symbol: q.symbol,
    // interval 去 _ 前缀:前端 tab value "_15m" → 后端 Interval::fromCcxt 只认 "15m"(ccxtValue)。
    interval: q.interval.replace(/^_/, ''),
  })
  if (q.limit) params.set('limit', String(q.limit))
  if (q.before) params.set('before', q.before)
  return apiFetch<Kline[]>(`/api/v1/market/klines?${params}`)
}

/** WS 订阅(body SubscribeRequest)。POST /subscribe 起后端按需 ticker worker(idle 30s 退订);前端 marketStore.subscribeTicker 订 destination 收 WS。 */
export function subscribeMarket(body: SubscribeRequest): Promise<string> {
  return apiFetch<string>('/api/v1/market/subscribe', { method: 'POST', body })
}

/** WS 退订。 */
export function unsubscribeMarket(body: SubscribeRequest): Promise<string> {
  return apiFetch<string>('/api/v1/market/unsubscribe', { method: 'POST', body })
}

/**
 * kline 订阅/退订请求(interval 用 ccxtValue "15m",与 WS destination 段一致)。
 * honest TD:后端新加 KlineSubscribeRequest record,api-gen.ts 还没更新(后端窗口 gen:api);
 * 前端临时自定类型,gen:api 更新后改用 api-gen 同名 DTO。
 */
export interface KlineSubscribeRequest {
  exchange: string
  marketType: string
  symbol: string
  interval: string // ccxtValue "1m"|"15m"|"1h"...
}

/** 订阅 K 线(POST /subscribe/kline,后端按需起 kline worker,idle 30s 退订)。 */
export function subscribeKlineMarket(body: KlineSubscribeRequest): Promise<string> {
  return apiFetch<string>('/api/v1/market/subscribe/kline', { method: 'POST', body })
}

/** 退订 K 线(POST /unsubscribe/kline,按 interval 退,不影响 ticker)。 */
export function unsubscribeKlineMarket(body: KlineSubscribeRequest): Promise<string> {
  return apiFetch<string>('/api/v1/market/unsubscribe/kline', { method: 'POST', body })
}

/** re-export 类型供 hooks/page 用。 */
export type { TickerResponse, TradingPairInfo, Kline, SubscribeRequest }
