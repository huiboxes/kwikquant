import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchStrategies, stopStrategy } from '@/api/strategy'
import { strategyKeys } from '@/api/_queryKeys'

/**
 * useStrategies — 查询当前用户策略列表(react-query)。
 * **只建 list + stop,其他 hook(get/create/codes/publish/start/ready/pause)留 StrategyPage 任务。**
 */
export function useStrategies() {
  return useQuery({
    queryKey: strategyKeys.list(),
    queryFn: fetchStrategies,
  })
}

/**
 * useStopStrategy — 停止单个策略(POST /stop。RUNNING/PAUSED/ERROR→STOPPED)。
 * 单个停止用;紧急停止批量用 Promise.allSettled + 裸 stopStrategy(api 函数)。
 */
export function useStopStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => stopStrategy(id),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
    },
  })
}
