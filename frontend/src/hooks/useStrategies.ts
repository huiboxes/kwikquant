import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchStrategies,
  stopStrategy,
  pauseStrategy,
  startStrategy,
} from '@/api/strategy'
import { strategyKeys } from '@/api/_queryKeys'

/**
 * useStrategies — 查询当前用户策略列表(react-query)。
 * **list + stop + pause + start;其他 hook(get/create/codes/publish/ready)留 StrategyPage 任务。**
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

/**
 * usePauseStrategy — 暂停单个策略(POST /pause。RUNNING→PAUSED)。
 * Dashboard 运行中策略卡"暂停"按钮用(ConfirmDialog destructive 后调)。
 */
export function usePauseStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => pauseStrategy(id),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
    },
  })
}

/**
 * useStartStrategy — 启动单个策略(POST /start。PAUSED→RUNNING)。
 * Dashboard 暂停策略"启动"按钮用(ConfirmDialog 后调)。
 */
export function useStartStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => startStrategy(id),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
    },
  })
}
