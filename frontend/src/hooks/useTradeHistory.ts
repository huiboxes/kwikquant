import { useQuery } from '@tanstack/react-query'
import {
  fetchTradeHistory,
  fetchTradeHistoryStats,
  type TradeHistoryQuery,
  type TradeHistoryStatsQuery,
} from '@/api/trade-history'
import { tradeHistoryKeys } from '@/api/_queryKeys'

/**
 * useTradeHistory — 分页查询交易历史(react-query)。
 * 筛选/分页参数变化 → queryKey 变 → 自动重查。staleTime 5s(queryClient 默认)。
 */
export function useTradeHistory(params: TradeHistoryQuery = {}) {
  return useQuery({
    queryKey: tradeHistoryKeys.list(params),
    queryFn: () => fetchTradeHistory(params),
  })
}

/** useTradeHistoryStats — 交易统计(成交额/累计手续费/已实现盈亏)。 */
export function useTradeHistoryStats(params: TradeHistoryStatsQuery = {}) {
  return useQuery({
    queryKey: tradeHistoryKeys.stats(params),
    queryFn: () => fetchTradeHistoryStats(params),
  })
}
