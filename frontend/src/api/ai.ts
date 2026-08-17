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
 *  - DELETE /api/v1/strategies/{id}/ai/messages   → Void(清空会话)
 *  - POST   /api/v1/ai/chat                        → SSE Flux(服务端存 user 消息 + 流正常结束时存 AI 回复,前端不单独存)
 */
type LlmApiKeyView = components['schemas']['LlmApiKeyView']
type CreateLlmKeyRequest = components['schemas']['CreateLlmKeyRequest']
type ChatMessage = components['schemas']['ChatMessage']
type AiChatRequest = components['schemas']['AiChatRequest']
type AiChatMessageView = components['schemas']['AiChatMessageView']
type LlmConnectionTestResult = components['schemas']['LlmConnectionTestResult']
type ApiResponseListLlmApiKeyView = components['schemas']['ApiResponseListLlmApiKeyView']
type ApiResponseLlmApiKeyView = components['schemas']['ApiResponseLlmApiKeyView']

export type {
  LlmApiKeyView,
  CreateLlmKeyRequest,
  ChatMessage,
  AiChatRequest,
  AiChatMessageView,
  LlmConnectionTestResult,
}

/**
 * AI 对话 SSE 流式请求体(streamChat<T> 的 T,Wave 3.2c 类型化)。
 *
 * 基于 api-gen AiChatRequest,但按运行时实际放宽:llmKeyId 可为 null(未选 key)、
 * messages 必带、codeSource 必带,temperature/maxTokens 省略(后端默认),
 * strategyId/model/sourceCode 条件可选。api-gen 把 AiChatRequest 全字段标 required
 * (springdoc 默认),此类型是对运行时契约的显式声明,编译期约束 body 结构。
 */
export type AiChatStreamRequest = Omit<Partial<AiChatRequest>, 'llmKeyId'> & {
  llmKeyId: number | null
  messages: ChatMessage[]
  codeSource: AiChatRequest['codeSource']
}

/** AI 对话 SSE 端点(POST /ai/chat;流式 Flux<ServerSentEvent>,不套 ApiResponse envelope)。 */
export const AI_CHAT_URL = '/api/v1/ai/chat'

/** LLM provider 枚举(契约 api-gen)。 */
export type LlmProvider = LlmApiKeyView['provider']

/** provider → 中文 label(原型 k.provider 是中文字符串,契约是枚举,page 层映射)。 */
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

/** 清空某策略会话历史(ConfirmDialog 后调)。 */
export function clearChatHistory(strategyId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/strategies/${strategyId}/ai/messages`, { method: 'DELETE' })
}

/**
 * 测 LLM Key 连通性(POST /api/v1/ai/keys/{id}/test?model=...)。
 * 后端用该 key + model 发最小 ping(messages=[hi], max_tokens=1, 10s 超时),复用 sanitize 脱敏,
 * 不透传 provider 原始错误。settings 加 key 表单「保存并测试」+ key 卡片「测试连通性」用。
 */
export function testConnection(id: number, model: string): Promise<LlmConnectionTestResult> {
  const search = new URLSearchParams({ model })
  return apiFetch<LlmConnectionTestResult>(`/api/v1/ai/keys/${id}/test?${search.toString()}`, {
    method: 'POST',
  })
}

/** 响应 envelope 类型 re-export(page 层需要时用)。 */
export type { ApiResponseListLlmApiKeyView, ApiResponseLlmApiKeyView }
