import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/types/api-gen'
import {
  fetchRiskPolicies,
  fetchRiskDecisions,
  toggleRiskPolicy,
  createRiskPolicy,
  updateRiskPolicy,
  deleteRiskPolicy,
  applyRiskRules,
  type RiskDecisionQuery,
  type RiskPolicyRequest,
  type RiskPolicyApplyBody,
} from '@/api/risk'
import { parseRiskRules, type RiskPolicyParseBody } from '@/api/ai'
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
 * 乐观更新:setQueryData 即时翻 enabled;onError 回滚；onSettled invalidate。
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

/** useCreateRiskPolicy — 新建风控策略(POST)。不乐观插临时项(无 id),onSettled invalidate 拉真值。 */
export function useCreateRiskPolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: RiskPolicyRequest) => createRiskPolicy(body),
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: riskKeys.list() })
      const prev = queryClient.getQueryData<RiskPolicyDto[]>(riskKeys.list())
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

/** useUpdateRiskPolicy — 更新 name/params(PUT)。乐观改匹配 id 项的 name/params。 */
export function useUpdateRiskPolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ policyId, body }: { policyId: number; body: RiskPolicyRequest }) =>
      updateRiskPolicy(policyId, body),
    onMutate: async ({ policyId, body }) => {
      await queryClient.cancelQueries({ queryKey: riskKeys.list() })
      const prev = queryClient.getQueryData<RiskPolicyDto[]>(riskKeys.list())
      queryClient.setQueryData<RiskPolicyDto[]>(riskKeys.list(), (old) =>
        old?.map((p) => (p.id === policyId ? { ...p, name: body.name, params: body.params } : p)),
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

/**
 * useParseRiskRules — 自然语言风控解析。同步 POST(非 SSE)，无缓存纯 mutation;
 * 失败(8004 无法识别 / 8003 provider 错误)由调用方读 ApiError.message 展示。
 */
export function useParseRiskRules() {
  return useMutation({
    mutationFn: (body: RiskPolicyParseBody) => parseRiskRules(body),
  })
}

/**
 * useApplyRiskRules — 批量原子落库(确认步)。后端单事务，前端无需乐观更新
 * (要么全部生效要么整体回滚),onSettled invalidate 拉真值。
 */
export function useApplyRiskRules() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: RiskPolicyApplyBody) => applyRiskRules(body),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: riskKeys.all })
    },
  })
}

/** useDeleteRiskPolicy — 删除风控策略(DELETE)。乐观删匹配 id 项。 */
export function useDeleteRiskPolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (policyId: number) => deleteRiskPolicy(policyId),
    onMutate: async (policyId) => {
      await queryClient.cancelQueries({ queryKey: riskKeys.list() })
      const prev = queryClient.getQueryData<RiskPolicyDto[]>(riskKeys.list())
      queryClient.setQueryData<RiskPolicyDto[]>(riskKeys.list(), (old) =>
        old?.filter((p) => p.id !== policyId),
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
