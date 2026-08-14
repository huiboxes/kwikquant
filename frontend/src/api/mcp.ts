import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * mcp typed client(MCP PAT 令牌;SettingsPage mcp tab 用)。
 *
 * 端点(均 JWT):
 *  - GET    /api/v1/mcp/tokens       → List McpTokenView(含 scopes/过期/状态,不含明文)
 *  - POST   /api/v1/mcp/tokens       body CreateMcpTokenRequest{name,scopes?,expiresInDays?}
 *                                     → McpTokenIssueResult(明文 token 仅此一次)
 *  - DELETE /api/v1/mcp/tokens/{id}  → Void(吊销)
 *
 * 实现说明:
 *  - PAT scope 真实生效(后端 McpScopeGuard 校验):READ/BACKTEST/TRADE/LIVE/RISK 5 档。
 *    签发默认仅 READ(最小权限),写/高危 scope 显式勾选。高危写操作另走两阶段 confirmToken
 *    (见后端 McpConfirmTokenService),scope 与确认是两层独立防护。
 *  - expiresInDays 缺省 90 天(上限 365),杜绝永久全权凭证。
 *  - 明文 token 契约 "kq_pat_<32hex>"。active 派生:`!revokedAt`;lastUsedAt null=从未使用。
 */
type McpTokenView = components['schemas']['McpTokenView']
type McpTokenIssueResult = components['schemas']['McpTokenIssueResult']
type CreateMcpTokenRequest = components['schemas']['CreateMcpTokenRequest']

export type { McpTokenView, McpTokenIssueResult, CreateMcpTokenRequest }

/** PAT 权限域(与后端 McpTokenScope 对齐,粗粒度 5 档)。 */
export const MCP_SCOPES = ['READ', 'BACKTEST', 'TRADE', 'LIVE', 'RISK'] as const
export type McpScope = (typeof MCP_SCOPES)[number]
/** 高危 scope(实盘真实下单/全局风控):勾选时 UI 强提示。 */
export const HIGH_RISK_SCOPES: ReadonlySet<McpScope> = new Set(['LIVE', 'RISK'])
/** scope 中文标签(展示用)。 */
export const MCP_SCOPE_LABELS: Record<McpScope, string> = {
  READ: '只读',
  BACKTEST: '回测',
  TRADE: '交易(含模拟盘)',
  LIVE: '启动实盘',
  RISK: '风控规则/急停',
}

/** 查 MCP token 列表(含 scopes/过期/状态,不含明文)。SettingsPage mcp tab 数据源。 */
export function fetchMcpTokens(): Promise<McpTokenView[]> {
  return apiFetch<McpTokenView[]>('/api/v1/mcp/tokens')
}

/** 签发 MCP token(scopes/expiresInDays 可选;明文 token 仅此响应可见)。 */
export function issueMcpToken(req: CreateMcpTokenRequest): Promise<McpTokenIssueResult> {
  return apiFetch<McpTokenIssueResult>('/api/v1/mcp/tokens', { method: 'POST', body: req })
}

/** 吊销 MCP token(DELETE)。吊销 ConfirmDialog destructive 真调。 */
export function revokeMcpToken(id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/mcp/tokens/${id}`, { method: 'DELETE' })
}
