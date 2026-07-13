import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/types/api-gen'
import {
  fetchRiskPolicies,
  fetchRiskDecisions,
  toggleRiskPolicy,
  type RiskDecisionQuery,
} from '@/api/risk'
import { riskKeys } from '@/api/_queryKeys'

type RiskPolicyDto = components['schemas']['RiskPolicyDto']

/**
 * useRiskPolicies — 查询当前用户所有账户的风控策略(react-query)。
 * toggle mutation 乐观更新此 query 的缓存。
 */
export function useRiskPolicies() {
  return useQuery({
    queryKey: riskKeys.list(),
    queryFn: fetchRiskPolicies,
  })
}

/** useRiskDecisions — 分页查询风控决策审计日志。 */
export function useRiskDecisions(params: RiskDecisionQuery = {}) {
  return useQuery({
    queryKey: riskKeys.decisions(params),
    queryFn: () => fetchRiskDecisions(params),
  })
}

/**
 * useToggleRiskPolicy — 启停风控策略(⚠ PATCH /toggle)。
 * 乐观更新:setQueryData 即时翻 enabled;onError 回滚;onSettled invalidate。
 */
export function useToggleRiskPolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ policyId, enabled }: { policyId: number; enabled: boolean }) =>
      toggleRiskPolicy(policyId, enabled),
    onMutate: async ({ policyId, enabled }) => {
      await queryClient.cancelQueries({ queryKey: riskKeys.list() })
      const prev = queryClient.getQueryData<RiskPolicyDto[]>(riskKeys.list())
      queryClient.setQueryData<RiskPolicyDto[]>(riskKeys.list(), (old) =>
        old?.map((p) => (p.id === policyId ? { ...p, enabled } : p)),
      )
      return { prev }
    },
    onError: (_e, _vars, ctx) => {
      if (ctx?.prev) queryClient.setQueryData(riskKeys.list(), ctx.prev)
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: riskKeys.all })
    },
  })
}
