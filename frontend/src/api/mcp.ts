import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * mcp typed client(MCP PAT 令牌;SettingsPage mcp tab 用)。
 *
 * 端点(均 JWT):
 *  - GET    /api/v1/mcp/tokens       → List McpTokenView(元信息 name/创建/状态,不含明文)
 *  - POST   /api/v1/mcp/tokens       body CreateMcpTokenRequest{name} → McpTokenIssueResult(明文 token 仅此一次)
 *  - DELETE /api/v1/mcp/tokens/{id}  → Void(吊销)
 *
 * honest:
 *  - McpTokenView 无 scopes 字段(原型 t.scopes 展 chip),PAT 是全权限不分 scope,MCP agent
 *    能做所有操作(高风险走二次确认 flow 兜底)。签发 modal scopes 勾选 UI 保留(照原型)但
 *    **不传后端**(CreateMcpTokenRequest 只要 name);列表卡不展 scopes TD-025。
 *  - 明文 token 契约 "kq_pat_<32hex>",原型 "kq_live_xxxxxxxxxxxx_{id}"。port 用契约真实 token。
 *  - active 派生:`!revokedAt`(revokedAt null=有效)。lastUsedAt null=从未使用。
 */
type McpTokenView = components['schemas']['McpTokenView']
type McpTokenIssueResult = components['schemas']['McpTokenIssueResult']
type CreateMcpTokenRequest = components['schemas']['CreateMcpTokenRequest']

export type { McpTokenView, McpTokenIssueResult, CreateMcpTokenRequest }

/** 查 MCP token 列表(仅元信息,不含明文)。SettingsPage mcp tab 数据源。 */
export function fetchMcpTokens(): Promise<McpTokenView[]> {
  return apiFetch<McpTokenView[]>('/api/v1/mcp/tokens')
}

/** 签发 MCP token(body 只要 name;明文 token 仅此响应可见,page 层转 McpReveal modal 展示)。 */
export function issueMcpToken(req: CreateMcpTokenRequest): Promise<McpTokenIssueResult> {
  return apiFetch<McpTokenIssueResult>('/api/v1/mcp/tokens', { method: 'POST', body: req })
}

/** 吊销 MCP token(DELETE)。吊销 ConfirmDialog destructive 真调。 */
export function revokeMcpToken(id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/mcp/tokens/${id}`, { method: 'DELETE' })
}
