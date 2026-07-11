import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * risk typed client。
 *
 * 端点(均 JWT):
 *  - GET    /api/v1/risk/policies               → RiskPolicyDto[](当前用户所有账户策略)
 *  - POST   /api/v1/risk/policies               → RiskPolicyDto(新建,原型无 UI,备用)
 *  - PUT    /api/v1/risk/policies/{policyId}     → RiskPolicyDto(更新,原型无 UI,备用)
 *  - DELETE /api/v1/risk/policies/{policyId}     → 204(删除,原型无 UI,备用)
 *  - PATCH  /api/v1/risk/policies/{policyId}/toggle → RiskPolicyDto(启停 ⚠ PATCH 不是 POST)
 *  - GET    /api/v1/risk/decisions               → PageDtoRiskDecisionDto(决策审计)
 *
 * 金额:params.maxNotionalUsdt/maxLossUsdt 后端序列化为 string,展示用 decimal.js。
 */
type RiskPolicyDto = components['schemas']['RiskPolicyDto']
type RiskPolicyRequest = components['schemas']['RiskPolicyRequest']
type ToggleRequest = components['schemas']['ToggleRequest']
type PageDtoRiskDecisionDto = components['schemas']['PageDtoRiskDecisionDto']

export interface RiskDecisionQuery {
  accountId?: number
  orderId?: number
  verdict?: 'APPROVED' | 'REJECTED'
  startTime?: string
  endTime?: string
  page?: number
  pageSize?: number
}

function toQs(params: object): string {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v != null && (typeof v === 'string' || typeof v === 'number')) qs.set(k, String(v))
  }
  const s = qs.toString()
  return s ? `?${s}` : ''
}

/** 查询当前用户所有账户的风控策略列表。 */
export function fetchRiskPolicies(): Promise<RiskPolicyDto[]> {
  return apiFetch<RiskPolicyDto[]>('/api/v1/risk/policies')
}

/**
 * 启停风控策略(⚠ PATCH,不是 POST)。
 * body ToggleRequest{enabled}。策略不存在或非本人返回 409(4009)。
 */
export function toggleRiskPolicy(policyId: number, enabled: boolean): Promise<RiskPolicyDto> {
  const body: ToggleRequest = { enabled }
  return apiFetch<RiskPolicyDto>(`/api/v1/risk/policies/${policyId}/toggle`, {
    method: 'PATCH',
    body,
  })
}

/**
 * 分页查询风控决策(脱敏审计日志)。
 * 返 PageDtoRiskDecisionDto(content: RiskDecisionDto[])。
 */
export function fetchRiskDecisions(params: RiskDecisionQuery = {}): Promise<PageDtoRiskDecisionDto> {
  return apiFetch<PageDtoRiskDecisionDto>(`/api/v1/risk/decisions${toQs(params)}`)
}

// ─── 备用:原型无 UI 但 typed client 全套(符合 plan 阶段 3 精神)───

/** 新建风控策略(POST)。原型 RiskPage 无"新建规则"UI,备用。 */
export function createRiskPolicy(body: RiskPolicyRequest): Promise<RiskPolicyDto> {
  return apiFetch<RiskPolicyDto>('/api/v1/risk/policies', { method: 'POST', body })
}

/** 更新风控策略(PUT)。原型"保存规则"按钮只 toast 无编辑 modal,备用。 */
export function updateRiskPolicy(
  policyId: number,
  body: RiskPolicyRequest,
): Promise<RiskPolicyDto> {
  return apiFetch<RiskPolicyDto>(`/api/v1/risk/policies/${policyId}`, { method: 'PUT', body })
}

/**
 * 删除风控策略(DELETE → 204 No Content)。
 * 注意:204 无 body,apiFetch 的 parseBody(res.json)会抛 SyntaxError —— 此处 catch 放行。
 * 原型无删除 UI,备用。
 */
export async function deleteRiskPolicy(policyId: number): Promise<void> {
  try {
    await apiFetch<void>(`/api/v1/risk/policies/${policyId}`, { method: 'DELETE' })
  } catch (e) {
    // 204 No Content 无 body,res.json() 抛 SyntaxError 是预期的,不视为错误
    if (e instanceof SyntaxError) return
    throw e
  }
}
