import { create } from 'zustand'
import type { ChatMessageInput } from '@/schemas/ai-chat'

/**
 * aiChatStore — AI 对话状态(spec §5 step 14)。
 *
 * messages:对话历史(≤100 条,超限提示用户清理)。streaming:SSE 进行中。
 * 发送流程:addUserMessage → startAssistant(空 assistant 占位)→ appendToLastAssistant(chunk)
 *   → onClose setStreaming(false)。
 * 401 不重放:streamChat 抛 ApiError(1001),AISidebar toast 提示,不走全局 401 拦截器。
 */
type ChatMessage = ChatMessageInput

interface AiChatState {
  messages: ChatMessage[]
  streaming: boolean
  llmKeyId: number | null
  setLlmKeyId: (id: number | null) => void
  addUserMessage: (content: string) => void
  startAssistant: () => void
  appendToLastAssistant: (chunk: string) => void
  markLastAssistantError: (msg: string) => void
  setStreaming: (b: boolean) => void
  clear: () => void
}

export const useAiChatStore = create<AiChatState>((set) => ({
  messages: [],
  streaming: false,
  llmKeyId: null,

  setLlmKeyId: (id) => set({ llmKeyId: id }),

  addUserMessage: (content) =>
    set((s) => ({
      messages: [...s.messages, { role: 'user', content }],
    })),

  startAssistant: () =>
    set((s) => ({
      messages: [...s.messages, { role: 'assistant', content: '' }],
      streaming: true,
    })),

  appendToLastAssistant: (chunk) =>
    set((s) => {
      const msgs = [...s.messages]
      const last = msgs[msgs.length - 1]
      if (last && last.role === 'assistant') {
        msgs[msgs.length - 1] = { ...last, content: last.content + chunk }
      }
      return { messages: msgs }
    }),

  markLastAssistantError: (msg) =>
    set((s) => {
      const msgs = [...s.messages]
      const last = msgs[msgs.length - 1]
      if (last && last.role === 'assistant' && last.content === '') {
        msgs[msgs.length - 1] = { ...last, content: `[错误] ${msg}` }
      }
      return { messages: msgs }
    }),

  setStreaming: (b) => set({ streaming: b }),
  clear: () => set({ messages: [], streaming: false }),
}))
