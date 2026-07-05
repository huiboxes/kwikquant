import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'
import type { UpdateStrategyInput } from '@/schemas/strategy'
import type { StrategyDetailDto } from './useStrategies'

/**
 * useUpdateStrategy — 更新策略 mutation(spec §5 step 12)。
 * 成功:invalidate ['strategies'] + ['strategies', id] + toast。
 */
export function useUpdateStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id: number; input: UpdateStrategyInput }) =>
      apiFetch<StrategyDetailDto>(`/api/v1/strategies/${id}`, {
        method: 'PUT',
        body: input,
      }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['strategies'] })
      queryClient.invalidateQueries({ queryKey: ['strategies', data.id] })
      toast.success('策略已更新')
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '更新失败,请重试'
      toast.error(msg)
    },
  })
}
