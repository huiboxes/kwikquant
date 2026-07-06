import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { apiFetch, ApiError } from '@/lib/http'
import { toast } from 'sonner'
import type { CreateStrategyInput } from '@/schemas/strategy'
import type { StrategyDetailDto } from './useStrategies'
import { STRATEGY_TEMPLATE } from '@/lib/strategyTemplate'

/**
 * useCreateStrategy — 创建策略 + 初始 DRAFT code(spec §5 step 12)。
 *
 * 成功:POST /strategies + POST /:id/codes(STRATEGY_TEMPLATE 初始模板) → invalidate + 跳工作区编码态。
 * 建 DRAFT code 是关键:否则工作区 useStrategyCodes 返空 → codeId=null → 发布按钮 disabled。
 * 已有 DRAFT(409) 不阻塞,工作区会拉到已有 code。
 */
export function useCreateStrategy() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  return useMutation({
    mutationFn: async (input: CreateStrategyInput) => {
      const strategy = await apiFetch<StrategyDetailDto>('/api/v1/strategies', {
        method: 'POST',
        body: input,
      })
      // 建初始 DRAFT code(STRATEGY_TEMPLATE);已有 DRAFT(409) 等不阻塞
      try {
        await apiFetch(`/api/v1/strategies/${strategy.id}/codes`, {
          method: 'POST',
          body: { sourceCode: STRATEGY_TEMPLATE, changelog: 'initial template' },
        })
      } catch {
        // 409 已有 DRAFT 等不阻塞,工作区 useStrategyCodes 会拉到已有 code
      }
      return strategy
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['strategies'] })
      queryClient.invalidateQueries({ queryKey: ['strategies', data.id, 'codes'] })
      toast.success('策略已创建')
      navigate(`/strategies/${data.id}?stage=code`)
    },
    onError: (e) => {
      const msg = e instanceof ApiError ? e.message : '创建失败,请重试'
      toast.error(msg)
    },
  })
}
