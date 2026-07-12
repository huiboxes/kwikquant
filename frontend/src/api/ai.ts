import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * ai typed client(LLM API Keys;SettingsPage llm tab 用)。
 *
 * 端点(均 JWT):
 *  - GET    /api/v1/ai/keys        → List LlmApiKeyView(元信息 + 末4位明文,不含完整 key)
 *  - POST   /api/v1/ai/keys        body CreateLlmKeyRequest → LlmApiKeyView(key 加密 AES-256-GCM)
 *  - DELETE /api/v1/ai/keys/{id}  → Void(越权/不存在 409 4009)
 *
 * honest:LlmApiKeyView 无 active/enabled 字段(原型 k.active 展"启用"徽章),port 不展 TD-024。
 * provider 枚举 OPENAI|ANTHROPIC|OPENAI_COMPATIBLE,page 层 providerLabel 映射中文 TD-029。
 */
type LlmApiKeyView = components['schemas']['LlmApiKeyView']
type CreateLlmKeyRequest = components['schemas']['CreateLlmKeyRequest']
type ApiResponseListLlmApiKeyView = components['schemas']['ApiResponseListLlmApiKeyView']
type ApiResponseLlmApiKeyView = components['schemas']['ApiResponseLlmApiKeyView']

export type { LlmApiKeyView, CreateLlmKeyRequest }

/** LLM provider 枚举(契约 api-gen)。 */
export type LlmProvider = LlmApiKeyView['provider']

/** provider → 中文 label(原型 k.provider 是中文字符串,契约是枚举,page 层映射 TD-029)。 */
export function providerLabel(provider: LlmProvider): string {
  switch (provider) {
    case 'OPENAI':
      return 'OpenAI'
    case 'ANTHROPIC':
      return 'Anthropic'
    case 'OPENAI_COMPATIBLE':
      return 'OpenAI 兼容 (DeepSeek 等)'
    default:
      return provider
  }
}

/** 查 LLM key 列表(仅元信息 + 末4位明文)。SettingsPage llm tab 数据源。 */
export function fetchLlmKeys(): Promise<LlmApiKeyView[]> {
  return apiFetch<LlmApiKeyView[]>('/api/v1/ai/keys')
}

/** 创建 LLM key(完整 key 加密存储,响应仅返末4位)。AddLlm modal 用。 */
export function createLlmKey(req: CreateLlmKeyRequest): Promise<LlmApiKeyView> {
  return apiFetch<LlmApiKeyView>('/api/v1/ai/keys', { method: 'POST', body: req })
}

/** 删 LLM key(仅可删本人;越权/不存在 409)。删 key ConfirmDialog destructive 真调。 */
export function deleteLlmKey(id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/ai/keys/${id}`, { method: 'DELETE' })
}

/** 响应 envelope 类型 re-export(page 层需要时用)。 */
export type { ApiResponseListLlmApiKeyView, ApiResponseLlmApiKeyView }
