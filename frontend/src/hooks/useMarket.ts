import { useQuery, useMutation, useQueries } from '@tanstack/react-query'
import {
  fetchTicker,
  fetchPairs,
  fetchKlines,
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
 * honest(TD-008):后端无"列表 ticker"端点,前端 hardcode symbol 列表循环 GET。 */
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

/** useKlines — 历史 K 线(symbol/interval 变化重取)。 */
export function useKlines(q: KlinesQuery) {
  return useQuery({
    queryKey: marketKeys.klines(q),
    queryFn: () => fetchKlines(q),
    enabled: !!q.symbol,
  })
}

/** useSparklines — 批量 symbol 火花线趋势(每卡拉 /klines 1m limit 30,取 close 数组做趋势线)。
 * honest(TD-008):后端无"列表 kline"端点,前端 hardcode symbol 循环 useQueries 批量(同 useTickers 模式)。 */
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
