import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'
import type { StrategyCodeDto } from './useStrategyCode'

/**
 * usePublishCode — 发布代码 mutation(spec §5 step 16)。
 * POST /:codeId/publish → 状态 DRAFT→PUBLISHED。
 * 成功:invalidate ['strategies', strategyId, 'codes'] + toast + StageBreadcrumb 可跳 backtest。
 */
export function usePublishCode() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ strategyId, codeId }: { strategyId: number; codeId: number }) =>
      apiFetch<StrategyCodeDto>(
        `/api/v1/strategies/${strategyId}/codes/${codeId}/publish`,
        { method: 'POST' },
      ),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({
        queryKey: ['strategies', variables.strategyId, 'codes'],
      })
      queryClient.invalidateQueries({ queryKey: ['strategies'] })
      toast.success('代码已发布,可进入回测')
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '发布失败,请重试'
      toast.error(msg)
    },
  })
}
