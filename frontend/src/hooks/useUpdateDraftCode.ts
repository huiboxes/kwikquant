import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'
import type { StrategyCodeDto } from './useStrategyCode'

export interface UpdateDraftInput {
  strategyId: number
  codeId: number
  sourceCode: string
  changelog?: string
}

/**
 * useUpdateDraftCode — 保存草稿(PUT /:id/codes/:codeId)。
 * 仅 DRAFT 可改;发布后冻结。成功 invalidate code detail + 静默 toast(自动保存不刷屏)。
 */
export function useUpdateDraftCode() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: UpdateDraftInput) =>
      apiFetch<StrategyCodeDto>(
        `/api/v1/strategies/${input.strategyId}/codes/${input.codeId}`,
        {
          method: 'PUT',
          body: {
            sourceCode: input.sourceCode,
            changelog: input.changelog ?? 'manual save',
          },
        },
      ),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({
        queryKey: ['strategies', variables.strategyId, 'codes', variables.codeId],
      })
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '保存失败,请重试'
      toast.error(msg)
    },
  })
}
