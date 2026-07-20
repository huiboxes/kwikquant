import { useQuery } from '@tanstack/react-query'
import { fetchTickers, type TickersQuery } from '@/api/market'

/**
 * useMarketTickers — 批量行情列表(MarketPage 行情页用)。
 *
 * 拿全量(后端默认 quoteVolume desc,limit 200),**前端本地 sort**(即时,点列头不 re-fetch)。
 * 删 placeholderData: keepPreviousData —— 切 tab 时显示 loading(LoadingState)而非旧数据,
 * 让用户感知"有反应"(keepPreviousData 切 tab 显示旧 SPOT 数据,用户以为没切换)。
 * staleTime 10s 对齐后端 Caffeine 10s 缓存。
 */
export function useMarketTickers(params: TickersQuery) {
  const { exchange, marketType, limit = 200, search } = params
  return useQuery({
    queryKey: ['market', 'tickers', exchange, marketType, limit, search ?? ''],
    queryFn: () => fetchTickers({ exchange, marketType, limit, search }),
    staleTime: 10_000,
  })
}
