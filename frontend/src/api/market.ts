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
 *
 * honest(记 docs/tech-debt.md TD-008~011):
 *  - TD-008 已清:GET /market/tickers batch 端点就绪,MarketPage 用 fetchTickers 1 次 batch(非循环 GET)
 *  - TD-009 已清:GET /market/orderbook 端点就绪,TradingPage 用 useOrderBook(MarketPage 不用 orderbook)
 *  - Heatmap 多周期后端无(ticker 单点 percentage),派生 mock(TD-010,HeatmapChart 留账)
 *  - subscribe/unsubscribe:WS 驱动(WS SUBSCRIBE 起 worker / UNSUBSCRIBE 退,去 persistent hack),
 *    不走 REST /subscribe(端点保留兼容,前端不再调,TD-011 落地)
 */
type TickerResponse = components['schemas']['TickerResponse']
type TradingPairInfo = components['schemas']['TradingPairInfo']
type Kline = components['schemas']['Kline']
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

// subscribe/unsubscribe REST 端点后端保留兼容,但前端走 WS 驱动(WS SUBSCRIBE/UNSUBSCRIBE),
// 不再封装 REST subscribe 函数(原 persistent hack,WS 驱动统一,无泄漏)。

/** re-export 类型供 hooks/page 用。 */
export type { TickerResponse, TradingPairInfo, Kline }
