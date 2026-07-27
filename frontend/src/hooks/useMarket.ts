import { useQuery } from '@tanstack/react-query'
import { fetchPairs, fetchKlines, fetchOrderBook, type KlinesQuery } from '@/api/market'
import { marketKeys } from '@/api/_queryKeys'

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
 * TradingPage/MarketPage 用真实端点,不再用派生 mock。
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

// subscribe/unsubscribe 走 WS 驱动(WS SUBSCRIBE/UNSUBSCRIBE,见 useSymbolSnapshot)。
