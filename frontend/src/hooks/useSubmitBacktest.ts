import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'
import type { components } from '@/types/api-gen'
import type { BacktestSubmitInput } from '@/schemas/backtest'

export type BacktestTaskDto = components['schemas']['BacktestTaskDto']

/**
 * useSubmitBacktest — 提交回测 mutation(spec §5 step 19)。
 *
 * POST /api/v1/backtests → 返 BacktestTaskDto(id, status=PENDING)。
 * parameters 对象 → JSON.stringify 发送(后端要 JSON 字符串,api-gen.ts:2183)。
 *
 * cache invalidate:['backtests'](列表)+ 不预填 taskId(调用方从 mutate 返值取 id 设 URL)。
 */
export function useSubmitBacktest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: BacktestSubmitInput) =>
      apiFetch<BacktestTaskDto>('/api/v1/backtests', {
        method: 'POST',
        body: { ...input, parameters: JSON.stringify(input.parameters) },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['backtests'] })
      toast.success('回测已提交,正在排队…')
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '提交失败,请重试'
      toast.error(msg)
    },
  })
}
