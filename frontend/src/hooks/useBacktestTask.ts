import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/http'
import { nextBacktestInterval } from '@/lib/backtestPolling'
import type { BacktestTaskDto } from './useSubmitBacktest'

/**
 * useBacktestTask — 回测任务轮询 query(spec §5 step 20)。
 *
 * refetchInterval 函数:2s/2s/4s/8s/10s(上限 10s),COMPLETED/FAILED 停(false)。
 * 不超时(behavior-contract.md §3 契约 C:回测跑几分钟,前端不主动放弃,轮询持续到终态)。
 *
 * cache key: ['backtests', id]。
 * react-query v5 refetchInterval 函数签名:(query) => number | false | undefined。
 */
export function useBacktestTask(id: number | null) {
  return useQuery({
    queryKey: ['backtests', id],
    queryFn: () => apiFetch<BacktestTaskDto>(`/api/v1/backtests/${id}`),
    enabled: id !== null,
    refetchInterval: (query) => {
      const data = query.state.data
      if (!data) return false
      const attempt = Math.max(0, query.state.dataUpdateCount - 1)
      return nextBacktestInterval(data.status, attempt)
    },
  })
}
