import { z } from 'zod'

/**
 * AI Chat 请求 schema(spec §5 step 7)。
 * 后端 AiChatRequest:{llmKeyId, messages(≤100), strategyId, model?, temperature?(0-2), maxTokens?(≤32768)}。
 * ChatMessage.role 后端 @Pattern 白名单 system|user|assistant。
 *
 * 前端必传 strategyId(从 URL :id 取),model/temperature/maxTokens 不传用后端默认。
 */
const CHAT_ROLES = ['system', 'user', 'assistant'] as const

export const chatMessageSchema = z.object({
  role: z.enum(CHAT_ROLES),
  content: z
    .string()
    .min(1, '消息内容不能为空')
    .max(8000, '单条消息最多 8000 字符'),
})

export const aiChatSchema = z.object({
  llmKeyId: z.number().int().positive('请选择 LLM key'),
  messages: z
    .array(chatMessageSchema)
    .max(100, '对话历史最多 100 条,请清理历史后重试'),
  strategyId: z.number().int().positive(),
  model: z.string().optional(),
  temperature: z.number().min(0).max(2).optional(),
  maxTokens: z.number().int().max(32768).optional(),
})

export type AiChatInput = z.infer<typeof aiChatSchema>
export type ChatMessageInput = z.infer<typeof chatMessageSchema>
