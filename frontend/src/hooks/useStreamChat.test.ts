import { renderHook, waitFor, act } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockStreamChat = vi.fn()
const mockFetchChatHistory = vi.fn()
const mockSaveAiMessage = vi.fn()

vi.mock('@/lib/sse', () => ({
  streamChat: (...args: unknown[]) => mockStreamChat(...args),
}))
vi.mock('@/api/ai', () => ({
  AI_CHAT_URL: '/api/v1/ai/chat',
  fetchChatHistory: (...args: unknown[]) => mockFetchChatHistory(...args),
  saveAiMessage: (...args: unknown[]) => mockSaveAiMessage(...args),
}))

import { useStreamChat } from './useStreamChat'

/**
 * useStreamChat 测试。覆盖:进入策略加载历史(useEffect fetch)+ model localStorage + send 带 model + onClose 保存 AI 回复。
 * streamChat/saveAiMessage/fetchChatHistory 全 vi.mock,零真实网络/SSE,确定性。
 */
describe('useStreamChat', () => {
  beforeEach(() => {
    localStorage.clear()
    mockStreamChat.mockReset()
    mockFetchChatHistory.mockReset().mockResolvedValue([])
    mockSaveAiMessage.mockReset().mockResolvedValue({})
  })

  it('进入 strategyId 加载历史(空则显欢迎语)', async () => {
    const { result } = renderHook(() => useStreamChat(1))
    await waitFor(() => {
      expect(mockFetchChatHistory).toHaveBeenCalledWith(1)
      expect(result.current.messages).toHaveLength(1)
      expect(result.current.messages[0].role).toBe('ai')
    })
  })

  it('strategyId==null 不加载,保持欢迎语', async () => {
    const { result } = renderHook(() => useStreamChat(null))
    expect(result.current.messages).toHaveLength(1)
    expect(result.current.messages[0].role).toBe('ai')
    expect(result.current.model).toBe('')
    expect(mockFetchChatHistory).not.toHaveBeenCalled()
  })

  it('model 从 localStorage 读取(strategyId 变化时)', async () => {
    localStorage.setItem('ai-chat-model-1', 'deepseek-chat')
    const { result } = renderHook(() => useStreamChat(1))
    await waitFor(() => {
      expect(result.current.model).toBe('deepseek-chat')
    })
  })

  it('model 留空时初始为空(后端用 key 默认)', async () => {
    const { result } = renderHook(() => useStreamChat(1))
    await waitFor(() => {
      expect(result.current.model).toBe('')
    })
  })

  it('send 带 model(从 localStorage) + streamChat 调用,body 含 model', async () => {
    localStorage.setItem('ai-chat-model-1', 'deepseek-chat')
    const { result } = renderHook(() => useStreamChat(1))
    await waitFor(() => expect(result.current.model).toBe('deepseek-chat'))

    mockStreamChat.mockImplementation(() => Promise.resolve())

    await act(async () => {
      result.current.send('帮我改进策略', 1)
    })

    expect(mockStreamChat).toHaveBeenCalledWith(
      '/api/v1/ai/chat',
      expect.objectContaining({ llmKeyId: 1, strategyId: 1, model: 'deepseek-chat' }),
      expect.anything(),
      expect.anything(),
      expect.anything(),
    )
    // localStorage 写回(send 时持久化)
    expect(localStorage.getItem('ai-chat-model-1')).toBe('deepseek-chat')
  })

  it('send onClose 保存 AI 回复(saveAiMessage 调用)', async () => {
    localStorage.setItem('ai-chat-model-1', 'deepseek-chat')
    const { result } = renderHook(() => useStreamChat(1))
    await waitFor(() => expect(result.current.model).toBe('deepseek-chat'))

    let handlers: {
      onChunk: (data: string) => void
      onError: (data: string) => void
      onClose: () => void
    }
    mockStreamChat.mockImplementation((_url, _body, _signal, h) => {
      handlers = h
      return Promise.resolve()
    })

    await act(async () => {
      result.current.send('帮我改进', 1)
    })

    // 模拟 streamText 累积(onChunk 调)
    await act(async () => {
      handlers!.onChunk('AI 建议把 ATR 改 2.0')
    })
    expect(result.current.streamText).toBe('AI 建议把 ATR 改 2.0')

    // 模拟 onClose(SSE 流结束)
    await act(async () => {
      handlers!.onClose()
    })

    // saveAiMessage 调用:保存 AI 回复(content=完整 streamText,model=本次用的)
    expect(mockSaveAiMessage).toHaveBeenCalledWith(1, {
      content: 'AI 建议把 ATR 改 2.0',
      model: 'deepseek-chat',
    })
    // AI 消息推入 messages
    expect(result.current.messages.some((m) => m.content === 'AI 建议把 ATR 改 2.0' && m.role === 'ai')).toBe(true)
  })

  it('send 无 llmKeyId 时 toast 警告,不调 streamChat', async () => {
    const { result } = renderHook(() => useStreamChat(1))
    await waitFor(() => expect(mockFetchChatHistory).toHaveBeenCalled())

    await act(async () => {
      result.current.send('text', null)
    })

    expect(mockStreamChat).not.toHaveBeenCalled()
  })
})
