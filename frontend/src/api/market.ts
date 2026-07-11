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
 *  - POST /api/v1/market/subscribe → WS 订阅(body SubscribeRequest)
 *  - POST /api/v1/market/unsubscribe → WS 退订
 *
 * honest(记 docs/tech-debt.md TD-008~011):
 *  - 后端无"列表所有 ticker"端点(单 symbol GET),MarketPage hardcode 主流 symbol 循环 GET(TD-008)
 *  - order book 后端无端点,MarketPage 硬编码 mock(TD-009)
 *  - Heatmap 多周期后端无(ticker 单点 percentage),派生 mock(TD-010)
 *  - subscribe/unsubscribe WS 推送管理推 marketStore 阶段4 补全,当前 POST 占位 toast(TD-011)
 */
type TickerResponse = components['schemas']['TickerResponse']
type TradingPairInfo = components['schemas']['TradingPairInfo']
type Kline = components['schemas']['Kline']
type SubscribeRequest = components['schemas']['SubscribeRequest']

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

export interface KlinesQuery {
  exchange: string
  marketType: string
  symbol: string
  interval: string // '_1m'|'_5m'|'_15m'|'_1h'|'_4h'|'_1d'
  limit?: number
}

/** 查历史 K 线(按交易所/市场/symbol/interval,limit 控制条数)。 */
export function fetchKlines(q: KlinesQuery): Promise<Kline[]> {
  const params = new URLSearchParams({
    exchange: q.exchange,
    marketType: q.marketType,
    symbol: symUrl(q.symbol),
    interval: q.interval,
  })
  if (q.limit) params.set('limit', String(q.limit))
  return apiFetch<Kline[]>(`/api/v1/market/klines?${params}`)
}

/** WS 订阅(body SubscribeRequest)。⚠ honest:WS 推送管理推 marketStore 阶段4 补全,当前 POST 占位。 */
export function subscribeMarket(body: SubscribeRequest): Promise<string> {
  return apiFetch<string>('/api/v1/market/subscribe', { method: 'POST', body })
}

/** WS 退订。 */
export function unsubscribeMarket(body: SubscribeRequest): Promise<string> {
  return apiFetch<string>('/api/v1/market/unsubscribe', { method: 'POST', body })
}

/** re-export 类型供 hooks/page 用。 */
export type { TickerResponse, TradingPairInfo, Kline, SubscribeRequest }
