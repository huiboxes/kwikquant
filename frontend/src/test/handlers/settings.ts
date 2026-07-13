import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * settings MSW handlers(LLM key + MCP token + 通知偏好 + 改密码;SettingsPage 用)。
 *
 * mock 数据照原型 AppContext llmKeys/mcpTokens 风格适配契约 DTO。
 *
 * honest:
 *  - LlmApiKeyView 无 active 字段(TD-024),mock 不设 active。
 *  - McpTokenView 无 scopes 字段(TD-025),mock 不设 scopes;active 派生 !revokedAt。
 *  - 明文 token 契约 "kq_pat_<32hex>",用确定性 hex(id 转 16 进制 padStart 32)
 *    避测试漂移(非 Math.random,envelope 注释原则)。
 *  - 通知偏好:GET 返已显式设置项(6 事件 × 2 明确渠道 WEBSOCKET/EMAIL 的子集),
 *    page 层 default matrix 填充未返回组合。
 *  - parseInt 替 Number(ID,金额红线);delete/revoke 返 204 无 body(apiFetch parseBody
 *    抛 SyntaxError 由 client catch 放行,见 account.ts deleteAccount 范式)。
 */
type LlmApiKeyView = components['schemas']['LlmApiKeyView']
type McpTokenView = components['schemas']['McpTokenView']
type McpTokenIssueResult = components['schemas']['McpTokenIssueResult']
type CreateLlmKeyRequest = components['schemas']['CreateLlmKeyRequest']
type CreateMcpTokenRequest = components['schemas']['CreateMcpTokenRequest']
type NotificationPreferenceDto = components['schemas']['NotificationPreferenceDto']
type PreferenceItem = components['schemas']['PreferenceItem']

// LLM keys(照原型:OpenAI gpt-5 风格 + Anthropic claude;apiKeyMasked 末4位)
const LLM_KEYS: LlmApiKeyView[] = [
  {
    id: 1,
    label: 'gpt-5 风格策略',
    provider: 'OPENAI',
    apiKeyMasked: '...6xyz',
    baseUrl: '',
    createdAt: '2026-07-08T10:24:00Z',
  },
  {
    id: 2,
    label: 'claude 深度分析',
    provider: 'ANTHROPIC',
    apiKeyMasked: '...9abc',
    baseUrl: '',
    createdAt: '2026-07-09T14:02:00Z',
  },
]

// MCP tokens(照原型:Cursor Agent 用过 + CI bot 从未用;revokedAt null=有效)
const MCP_TOKENS: McpTokenView[] = [
  {
    id: 1,
    name: 'Cursor Agent',
    createdAt: '2026-07-09T14:02:00Z',
    lastUsedAt: '2026-07-12T06:30:00Z',
    expiresAt: '',
    revokedAt: '',
  },
  {
    id: 2,
    name: 'CI Bot',
    createdAt: '2026-07-10T08:00:00Z',
    lastUsedAt: '',
    expiresAt: '',
    revokedAt: '',
  },
]

// 通知偏好(已显式设置项;6 事件 × 2 明确渠道 WEBSOCKET/EMAIL 的子集,部分 enabled=false=关闭推送)
const NOTIF_PREFS: NotificationPreferenceDto[] = [
  { id: 1, userId: 1, eventType: 'RISK_REJECTED', channelType: 'WEBSOCKET', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 2, userId: 1, eventType: 'RISK_REJECTED', channelType: 'EMAIL', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 3, userId: 1, eventType: 'ORDER_FILLED', channelType: 'WEBSOCKET', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 4, userId: 1, eventType: 'ORDER_FILLED', channelType: 'EMAIL', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 5, userId: 1, eventType: 'ORDER_CANCELLED', channelType: 'WEBSOCKET', enabled: false, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 6, userId: 1, eventType: 'ORDER_CANCELLED', channelType: 'EMAIL', enabled: false, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 7, userId: 1, eventType: 'STRATEGY_STARTED', channelType: 'WEBSOCKET', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 8, userId: 1, eventType: 'STRATEGY_STARTED', channelType: 'EMAIL', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 9, userId: 1, eventType: 'STRATEGY_STOPPED', channelType: 'WEBSOCKET', enabled: false, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 10, userId: 1, eventType: 'STRATEGY_STOPPED', channelType: 'EMAIL', enabled: false, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 11, userId: 1, eventType: 'STRATEGY_ERROR', channelType: 'WEBSOCKET', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
  { id: 12, userId: 1, eventType: 'STRATEGY_ERROR', channelType: 'EMAIL', enabled: true, createdAt: '2026-07-01T00:00:00Z', updatedAt: '2026-07-04T12:00:00Z' },
]

let nextLlmKeyId = 3
let nextMcpTokenId = 3

/** 末4位明文(mock:从 apiKey 取末4位,与后端 apiKeyMasked 语义对齐)。 */
function maskApiKey(apiKey: string): string {
  return apiKey.length <= 4 ? apiKey : `...${apiKey.slice(-4)}`
}

/** 确定性 32-hex token(id 转 16 进制 padStart 32,避 Math.random 测试漂移)。 */
function makeToken(id: number): string {
  const hex = id.toString(16).padStart(32, '0')
  return `kq_pat_${hex}`
}

export const settingsHandlers = [
  // GET /api/v1/ai/keys → LLM key 列表
  http.get('/api/v1/ai/keys', () => {
    return HttpResponse.json(envelope(LLM_KEYS))
  }),

  // POST /api/v1/ai/keys → 创建 LLM key(返末4位明文)
  http.post('/api/v1/ai/keys', async ({ request }) => {
    const body = (await request.json()) as CreateLlmKeyRequest
    const key: LlmApiKeyView = {
      id: nextLlmKeyId++,
      label: body.label,
      provider: body.provider,
      apiKeyMasked: maskApiKey(body.apiKey),
      baseUrl: body.baseUrl ?? '',
      createdAt: '2026-07-12T16:00:00Z',
    }
    LLM_KEYS.push(key)
    return HttpResponse.json(envelope(key))
  }),

  // DELETE /api/v1/ai/keys/:id → 204(无 body)
  http.delete('/api/v1/ai/keys/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const idx = LLM_KEYS.findIndex((k) => k.id === id)
    if (idx < 0) {
      return HttpResponse.json(envelope(null, 4009, '密钥不存在或非本人'), { status: 409 })
    }
    LLM_KEYS.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  // GET /api/v1/mcp/tokens → MCP token 列表
  http.get('/api/v1/mcp/tokens', () => {
    return HttpResponse.json(envelope(MCP_TOKENS))
  }),

  // POST /api/v1/mcp/tokens → 签发 MCP token(明文 token 仅此一次)
  http.post('/api/v1/mcp/tokens', async ({ request }) => {
    const body = (await request.json()) as CreateMcpTokenRequest
    const id = nextMcpTokenId++
    const token = makeToken(id)
    const view: McpTokenView = {
      id,
      name: body.name,
      createdAt: '2026-07-12T16:00:00Z',
      lastUsedAt: '',
      expiresAt: '',
      revokedAt: '',
    }
    MCP_TOKENS.push(view)
    const result: McpTokenIssueResult = {
      id,
      token,
      name: body.name,
      createdAt: '2026-07-12T16:00:00Z',
    }
    return HttpResponse.json(envelope(result))
  }),

  // DELETE /api/v1/mcp/tokens/:id → 204(吊销)
  http.delete('/api/v1/mcp/tokens/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const idx = MCP_TOKENS.findIndex((t) => t.id === id)
    if (idx < 0) {
      return HttpResponse.json(envelope(null, 4009, 'token 不存在或非本人'), { status: 409 })
    }
    MCP_TOKENS.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  // GET /api/v1/notifications/preferences → 偏好列表(已显式设置项)
  http.get('/api/v1/notifications/preferences', () => {
    return HttpResponse.json(envelope(NOTIF_PREFS))
  }),

  // PUT /api/v1/notifications/preferences → 幂等 upsert(返更新后列表)
  http.put('/api/v1/notifications/preferences', async ({ request }) => {
    const body = (await request.json()) as { preferences: PreferenceItem[] }
    for (const item of body.preferences) {
      const existing = NOTIF_PREFS.find(
        (p) => p.eventType === item.eventType && p.channelType === item.channelType,
      )
      if (existing) {
        existing.enabled = item.enabled
        existing.updatedAt = '2026-07-12T16:00:00Z'
      } else {
        NOTIF_PREFS.push({
          id: NOTIF_PREFS.length + 1,
          userId: 1,
          eventType: item.eventType,
          channelType: item.channelType,
          enabled: item.enabled,
          createdAt: '2026-07-12T16:00:00Z',
          updatedAt: '2026-07-12T16:00:00Z',
        })
      }
    }
    return HttpResponse.json(envelope(NOTIF_PREFS))
  }),

  // POST /api/v1/auth/change-password → 204(旧密码错 401 1001)
  http.post('/api/v1/auth/change-password', async ({ request }) => {
    const body = (await request.json()) as { oldPassword: string; newPassword: string }
    // mock:旧密码 "wrong" 触发 401(测试覆盖错误路径);其余成功
    if (body.oldPassword === 'wrong') {
      return HttpResponse.json(envelope(null, 1001, '旧密码错误'), { status: 401 })
    }
    return new HttpResponse(null, { status: 204 })
  }),
]
