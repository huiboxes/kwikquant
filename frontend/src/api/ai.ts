import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * ai typed client。
 *
 * 端点(均 JWT):
 *  - GET    /api/v1/ai/keys                        → List LlmApiKeyView
 *  - POST   /api/v1/ai/keys        body CreateLlmKeyRequest → LlmApiKeyView
 *  - DELETE /api/v1/ai/keys/{id}                  → Void
 *  - GET    /api/v1/strategies/{id}/ai/messages   → List AiChatMessageView(会话历史)
 *  - POST   /api/v1/strategies/{id}/ai/messages   body SaveAiMessageRequest → AiChatMessageView(SSE onClose 存 AI 回复)
 *  - DELETE /api/v1/strategies/{id}/ai/messages   → Void(清空会话)
 *  - POST   /api/v1/ai/chat                        → SSE Flux(内部存 user 消息,前端不单独存)
 */
type LlmApiKeyView = components['schemas']['LlmApiKeyView']
type CreateLlmKeyRequest = components['schemas']['CreateLlmKeyRequest']
type ChatMessage = components['schemas']['ChatMessage']
type AiChatRequest = components['schemas']['AiChatRequest']
type AiChatMessageView = components['schemas']['AiChatMessageView']
type SaveAiMessageRequest = components['schemas']['SaveAiMessageRequest']
type ApiResponseListLlmApiKeyView = components['schemas']['ApiResponseListLlmApiKeyView']
type ApiResponseLlmApiKeyView = components['schemas']['ApiResponseLlmApiKeyView']

export type {
  LlmApiKeyView,
  CreateLlmKeyRequest,
  ChatMessage,
  AiChatRequest,
  AiChatMessageView,
  SaveAiMessageRequest,
}

/** AI 对话 SSE 端点(POST /ai/chat;流式 Flux<ServerSentEvent>,不套 ApiResponse envelope)。 */
export const AI_CHAT_URL = '/api/v1/ai/chat'

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

/**
 * 查某策略 AI 会话历史(按时间升序,最近 200 条;strategy 不存在 404/7001,非本人 403/1002)。
 * SessionPanel 进入策略时加载,替换内存欢迎语。
 */
export function fetchChatHistory(strategyId: number): Promise<AiChatMessageView[]> {
  return apiFetch<AiChatMessageView[]>(`/api/v1/strategies/${strategyId}/ai/messages`)
}

/**
 * 保存 AI 回复(SSE onClose 时调;role=ai,content=完整回复文本,model=本次用的 model)。
 * user 消息由后端 POST /ai/chat 内部存,前端不单独存 user。
 */
export function saveAiMessage(
  strategyId: number,
  req: SaveAiMessageRequest,
): Promise<AiChatMessageView> {
  return apiFetch<AiChatMessageView>(`/api/v1/strategies/${strategyId}/ai/messages`, {
    method: 'POST',
    body: req,
  })
}

/** 清空某策略会话历史(ConfirmDialog 后调)。 */
export function clearChatHistory(strategyId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/strategies/${strategyId}/ai/messages`, { method: 'DELETE' })
}

/** 响应 envelope 类型 re-export(page 层需要时用)。 */
export type { ApiResponseListLlmApiKeyView, ApiResponseLlmApiKeyView }
