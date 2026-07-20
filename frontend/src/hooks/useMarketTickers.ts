import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { fetchTickers, type TickersQuery } from '@/api/market'

/**
 * useMarketTickers — 批量行情列表(MarketPage 行情页用)。
 *
 * 调 GET /market/tickers(1 次 fetchTickers 替 N 次 fetchTicker),staleTime 10s 对齐后端
 * Caffeine 10s 缓存。placeholderData: keepPreviousData 搜索/排序切换不闪。
 */
export function useMarketTickers(params: TickersQuery) {
  const { exchange, marketType, sort = 'quoteVolume', order = 'desc', limit = 200, search } = params
  return useQuery({
    queryKey: ['market', 'tickers', exchange, marketType, sort, order, limit, search ?? ''],
    queryFn: () => fetchTickers({ exchange, marketType, sort, order, limit, search }),
    staleTime: 10_000,
    placeholderData: keepPreviousData,
  })
}
