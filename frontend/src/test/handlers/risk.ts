import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * risk MSW handlers。
 * mock 数据照原型 AppContext.jsx riskRules(3 条)+ brief 指定值(5000/60/500)适配 RiskPolicyDto。
 * riskAudit 照原型(5 条)+ brief 指定 6 条 → 补 1 条 REJECTED,verdict/accountId 混合。
 * accountId 约定:1 = PAPER,2 = LIVE(同 trade-history)。
 * 拒绝原因脱敏(behavior-contract):REJECTED reason 不含阈值,只告知"被哪条规则拒";
 * APPROVED reason = null(契约"通过时为 null")→ AuditTable 详情列显示 —。
 */
type RiskPolicyDto = components['schemas']['RiskPolicyDto']
type RiskDecisionDto = components['schemas']['RiskDecisionDto']
type RuleResultDto = components['schemas']['RuleResultDto']

const POLICIES: RiskPolicyDto[] = [
  {
    id: 42,
    accountId: 1,
    ruleType: 'MAX_NOTIONAL',
    name: '单笔限额',
    params: { maxNotionalUsdt: '5000' },
    enabled: true,
    createdAt: '2026-07-01T08:00:00Z',
    updatedAt: '2026-07-09T10:00:00Z',
  },
  {
    id: 43,
    accountId: 1,
    ruleType: 'ORDER_FREQUENCY',
    name: '下单频率',
    params: { maxPerMinute: '60' },
    enabled: false,
    createdAt: '2026-07-01T08:00:00Z',
    updatedAt: '2026-07-09T10:00:00Z',
  },
  {
    id: 44,
    accountId: 1,
    ruleType: 'DAILY_LOSS_LIMIT',
    name: '日亏限额',
    params: { maxLossUsdt: '500' },
    enabled: true,
    createdAt: '2026-07-01T08:00:00Z',
    updatedAt: '2026-07-09T10:00:00Z',
  },
]

// RuleResultDto.reason 契约描述"通过时为 null",但 api-gen 标 string(springdoc 不标 nullable);
// 运行时 null 是真实的,helper 接受 string | null 并 cast 回类型。
function ruleResult(ruleType: string, passed: boolean, reason: string | null): RuleResultDto {
  return { ruleType, passed, reason: reason as unknown as string }
}

const DECISIONS: RiskDecisionDto[] = [
  {
    id: 512,
    orderId: 9001,
    accountId: 1,
    verdict: 'APPROVED',
    ruleResults: [ruleResult('MAX_NOTIONAL', true, null)],
    requestId: 'req-9001',
    createdAt: '2026-07-09T14:02:18Z',
  },
  {
    id: 513,
    orderId: 9002,
    accountId: 1,
    verdict: 'APPROVED',
    ruleResults: [ruleResult('DAILY_LOSS_LIMIT', true, null)],
    requestId: 'req-9002',
    createdAt: '2026-07-09T14:01:55Z',
  },
  {
    id: 514,
    orderId: 9003,
    accountId: 2,
    verdict: 'REJECTED',
    // 脱敏:只说"超出单笔限额",不告知阈值 5000(防探测)
    ruleResults: [ruleResult('MAX_NOTIONAL', false, '名义价值 11408 USDT,超出单笔限额')],
    requestId: 'req-9003',
    createdAt: '2026-07-09T13:58:42Z',
  },
  {
    id: 515,
    orderId: 9004,
    accountId: 2,
    verdict: 'APPROVED',
    ruleResults: [ruleResult('MAX_NOTIONAL', true, null)],
    requestId: 'req-9004',
    createdAt: '2026-07-09T13:42:10Z',
  },
  {
    id: 516,
    orderId: 9005,
    accountId: 1,
    verdict: 'APPROVED',
    ruleResults: [ruleResult('ORDER_FREQUENCY', true, null)],
    requestId: 'req-9005',
    createdAt: '2026-07-09T13:30:00Z',
  },
  {
    id: 517,
    orderId: 9006,
    accountId: 2,
    verdict: 'REJECTED',
    // 脱敏:只说"触及日亏限额",不告知阈值 500
    ruleResults: [ruleResult('DAILY_LOSS_LIMIT', false, '日累计已实现亏损,触及日亏限额')],
    requestId: 'req-9006',
    createdAt: '2026-07-09T12:10:00Z',
  },
]

export const riskHandlers = [
  // GET /api/v1/risk/policies → 当前用户所有账户策略
  http.get('/api/v1/risk/policies', () => {
    return HttpResponse.json(envelope(POLICIES))
  }),

  // PATCH /api/v1/risk/policies/{policyId}/toggle → 启停(⚠ PATCH 不是 POST)
  http.patch('/api/v1/risk/policies/:policyId/toggle', async ({ request, params }) => {
    const body = (await request.json()) as { enabled: boolean }
    const policyId = parseInt(params.policyId as string, 10)
    const policy = POLICIES.find((p) => p.id === policyId)
    if (!policy) {
      return HttpResponse.json(envelope(null, 4009, '策略不存在或非本人'), { status: 409 })
    }
    policy.enabled = body.enabled
    policy.updatedAt = new Date().toISOString()
    return HttpResponse.json(envelope(policy))
  }),

  // GET /api/v1/risk/decisions → 分页决策审计(mock 忽略 accountId/orderId,返全部)
  http.get('/api/v1/risk/decisions', ({ request }) => {
    const url = new URL(request.url)
    const page = Math.max(1, parseInt(url.searchParams.get('page') ?? '1', 10))
    const pageSize = Math.max(1, parseInt(url.searchParams.get('pageSize') ?? '10', 10))

    const total = DECISIONS.length
    const totalPages = Math.ceil(total / pageSize) || 1
    const content = DECISIONS.slice((page - 1) * pageSize, page * pageSize)

    return HttpResponse.json(
      envelope({ content, page, pageSize, total, totalPages }),
    )
  }),
]
