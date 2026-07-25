import { useQuery } from '@tanstack/react-query'
import {
  fetchTicker,
  fetchPairs,
  fetchKlines,
  fetchOrderBook,
  type KlinesQuery,
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

// subscribe/unsubscribe 走 WS 驱动(WS SUBSCRIBE 起 worker / UNSUBSCRIBE 退,见 useSymbolSnapshot),
// 不再有 REST /subscribe mutation(原 persistent hack,WS 驱动统一,无泄漏)。
