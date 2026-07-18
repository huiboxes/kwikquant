import { useQuery, useMutation, useQueries } from '@tanstack/react-query'
import {
  fetchTicker,
  fetchPairs,
  fetchKlines,
  fetchOrderBook,
  subscribeMarket,
  unsubscribeMarket,
  type KlinesQuery,
  type SubscribeRequest,
} from '@/api/market'
import { marketKeys } from '@/api/_queryKeys'

/** useTicker — 单 symbol 最新行情(返 ticker + stale)。 */
export function useTicker(
  exchange: string,
  marketType: string,
  symbol: string | undefined,
) {
  return useQuery({
    queryKey: marketKeys.ticker(exchange, marketType, symbol ?? ''),
    queryFn: () => fetchTicker(exchange, marketType, symbol as string),
    enabled: !!symbol,
  })
}

/** useTickers — 批量 symbol 行情(useQueries,每 symbol 一个 GET)。
 * honest(TD-008):后端无"列表 ticker"端点,前端产品精选 top 8 symbol(非 mock)循环 GET;
 * 中期后端 /market/pairs 加 volume 排序 top N 解 hardcode(留账)。 */
export function useTickers(
  exchange: string,
  marketType: string,
  symbols: string[],
) {
  return useQueries({
    queries: symbols.map((s) => ({
      queryKey: marketKeys.ticker(exchange, marketType, s),
      queryFn: () => fetchTicker(exchange, marketType, s),
      enabled: !!s,
    })),
  })
}

/** usePairs — 交易对列表(按交易所+市场类型)。 */
export function usePairs(exchange: string, marketType: string) {
  return useQuery({
    queryKey: marketKeys.pairs(exchange, marketType),
    queryFn: () => fetchPairs(exchange, marketType),
  })
}

/**
 * useOrderBook — 盘口深度(REST 轮询,后端无 orderbook WS,只有 ticker/kline WS)。
 * refetchInterval 3s 折中实时性 vs 请求成本;depth 默认 20(后端契约)。
 * TD-009 已接:替换 TradingPage/MarketPage 硬编码派生 mock。
 */
export function useOrderBook(
  exchange: string,
  marketType: string,
  symbol: string | undefined,
  depth?: number,
) {
  return useQuery({
    queryKey: marketKeys.orderbook(exchange, marketType, symbol ?? '', depth),
    queryFn: () => fetchOrderBook(exchange, marketType, symbol as string, depth),
    enabled: !!symbol,
    refetchInterval: 3000,
  })
}

/** useKlines — 历史 K 线(symbol/interval 变化重取)。 */
export function useKlines(q: KlinesQuery) {
  return useQuery({
    queryKey: marketKeys.klines(q),
    queryFn: () => fetchKlines(q),
    enabled: !!q.symbol,
  })
}

/** useSparklines — 批量 symbol 火花线趋势(每卡拉 /klines 1m limit 30,取 close 数组做趋势线)。
 * honest(TD-008):后端无"列表 kline"端点,前端产品精选 symbol 循环 useQueries 批量(同 useTickers 模式)。 */
export function useSparklines(exchange: string, marketType: string, symbols: string[]) {
  return useQueries({
    queries: symbols.map((s) => ({
      queryKey: marketKeys.klines({ exchange, marketType, symbol: s, interval: '_1m', limit: 30 }),
      queryFn: () => fetchKlines({ exchange, marketType, symbol: s, interval: '_1m', limit: 30 }),
      enabled: !!s,
    })),
  })
}

/** useSubscribeMarket — WS 订阅(占位 POST;WS 推送管理推 marketStore 阶段4 补全,TD-011)。 */
export function useSubscribeMarket() {
  return useMutation({
    mutationFn: (body: SubscribeRequest) => subscribeMarket(body),
  })
}

/** useUnsubscribeMarket — WS 退订。 */
export function useUnsubscribeMarket() {
  return useMutation({
    mutationFn: (body: SubscribeRequest) => unsubscribeMarket(body),
  })
}
