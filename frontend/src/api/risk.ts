import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * risk typed client。
 *
 * 端点(均 JWT):
 *  - GET    /api/v1/risk/policies               → RiskPolicyDto[](当前用户所有账户策略)
 *  - POST   /api/v1/risk/policies               → RiskPolicyDto(新建，原型无 UI，备用)
 *  - POST   /api/v1/risk/policies/apply         → RiskPolicyDto[](批量原子 create-or-update，自然语言风控确认落库)
 *  - PUT    /api/v1/risk/policies/{policyId}     → RiskPolicyDto(更新，原型无 UI，备用)
 *  - DELETE /api/v1/risk/policies/{policyId}     → 204(删除，原型无 UI，备用)
 *  - PATCH  /api/v1/risk/policies/{policyId}/toggle → RiskPolicyDto(启停 ⚠ PATCH 不是 POST)
 *  - GET    /api/v1/risk/decisions               → PageDtoRiskDecisionDto(决策审计)
 *
 * 金额:params.maxNotionalUsdt/maxLossUsdt 后端序列化为 string，展示用 decimal.js。
 */
type RiskPolicyDto = components['schemas']['RiskPolicyDto']
export type RiskPolicyRequest = components['schemas']['RiskPolicyRequest']
type RiskPolicyApplyRequest = components['schemas']['RiskPolicyApplyRequest']
type RiskPolicyApplyItemRequest = components['schemas']['RiskPolicyApplyItemRequest']
type ToggleRequest = components['schemas']['ToggleRequest']
type PageDtoRiskDecisionDto = components['schemas']['PageDtoRiskDecisionDto']

/**
 * 批量应用请求体运行时放宽包装(同 AiChatStreamRequest 先例):
 * api-gen 把 policyId 标 required(springdoc 默认)，运行时新建项省略 policyId(后端按无 policyId = 新建)。
 */
export type RiskPolicyApplyItemBody = Omit<RiskPolicyApplyItemRequest, 'policyId'> &
  Partial<Pick<RiskPolicyApplyItemRequest, 'policyId'>>
export type RiskPolicyApplyBody = Omit<RiskPolicyApplyRequest, 'rules'> & {
  rules: RiskPolicyApplyItemBody[]
}

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
 * 启停风控策略(⚠ PATCH，不是 POST)。
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

// ─── 备用：原型无 UI 但 typed client 全套 ───

/** 新建风控策略(POST)。原型 RiskPage 无"新建规则"UI，备用。 */
export function createRiskPolicy(body: RiskPolicyRequest): Promise<RiskPolicyDto> {
  return apiFetch<RiskPolicyDto>('/api/v1/risk/policies', { method: 'POST', body })
}

/**
 * 批量应用风控策略(POST /apply):单事务原子 create-or-update，任一失败整体回滚。
 * 自然语言风控"确认后落库":预览由 parseRiskRules(ai client)产出，本端点确定性落库。
 * rules 项带 policyId → 覆盖更新，省略 → 新建；冲突 409(2011)。
 */
export function applyRiskRules(body: RiskPolicyApplyBody): Promise<RiskPolicyDto[]> {
  return apiFetch<RiskPolicyDto[]>('/api/v1/risk/policies/apply', { method: 'POST', body })
}

/** 更新风控策略(PUT)。原型"保存规则"按钮只 toast 无编辑 modal，备用。 */
export function updateRiskPolicy(
  policyId: number,
  body: RiskPolicyRequest,
): Promise<RiskPolicyDto> {
  return apiFetch<RiskPolicyDto>(`/api/v1/risk/policies/${policyId}`, { method: 'PUT', body })
}

/**
 * 删除风控策略(DELETE → 204 No Content)。
 * 注意:204 无 body,apiFetch 的 parseBody(res.json)会抛 SyntaxError —— 此处 catch 放行。
 * 原型无删除 UI，备用。
 */
export async function deleteRiskPolicy(policyId: number): Promise<void> {
  try {
    await apiFetch<void>(`/api/v1/risk/policies/${policyId}`, { method: 'DELETE' })
  } catch (e) {
    // 204 No Content 无 body,res.json() 抛 SyntaxError 是预期的，不视为错误
    if (e instanceof SyntaxError) return
    throw e
  }
}
