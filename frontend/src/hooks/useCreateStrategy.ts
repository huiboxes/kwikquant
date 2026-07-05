import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'
import type { CreateStrategyInput } from '@/schemas/strategy'
import type { StrategyDetailDto } from './useStrategies'

/**
 * useCreateStrategy — 创建策略 mutation(spec §5 step 12)。
 * 成功:invalidate ['strategies'] + toast + 跳工作区编码态 /strategies/:id?stage=code。
 */
export function useCreateStrategy() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  return useMutation({
    mutationFn: (input: CreateStrategyInput) =>
      apiFetch<StrategyDetailDto>('/api/v1/strategies', {
        method: 'POST',
        body: input,
      }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['strategies'] })
      toast.success('策略已创建')
      navigate(`/strategies/${data.id}?stage=code`)
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '创建失败,请重试'
      toast.error(msg)
    },
  })
}
