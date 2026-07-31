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
vi.mock('sonner', () => ({ toast: { warning: vi.fn(), error: vi.fn() } }))

import { useAssistantChat } from './useAssistantChat'

/**
 * useAssistantChat 测试(ExternalStoreRuntime adapter 模式)。
 *
 * 复刻 spec  七项职责:messages+isRunning / history role 重映射 / abort / finalizedRef 防双错误 /
 * model localStorage / saveAiMessage / idle timeout 60s。streaming 文本进 messages 最后一条 assistant
 * partial content,非独立 streamText。
 *
 * streamChat/saveAiMessage/fetchChatHistory 全 vi.mock,零真实网络/SSE,确定性。
 */
describe('useAssistantChat', () => {
  beforeEach(() => {
    localStorage.clear()
    mockStreamChat.mockReset()
    mockFetchChatHistory.mockReset().mockResolvedValue([])
    mockSaveAiMessage.mockReset().mockResolvedValue({})
  })

  it('进入 strategyId 加载历史(空则显欢迎语)', async () => {
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => {
      expect(mockFetchChatHistory).toHaveBeenCalledWith(1)
      expect(result.current.messages).toHaveLength(1)
      expect(result.current.messages[0].role).toBe('assistant')
    })
  })

  it('历史消息 role 重映射:后端 ai → 前端 assistant(对齐 LLM 协议 system/user/assistant)', async () => {
    // 后端 DB 存 ai(AiChatController 硬编码),前端拿到历史要映射回 assistant,
    // 否则下次发请求 body.messages 带 'ai' 会被后端 ChatMessage @Pattern 拒(400 VALIDATION_FAILED)
    mockFetchChatHistory.mockResolvedValueOnce([
      { id: 1, strategyId: 1, role: 'user', content: '历史用户问', model: null, createdAt: '2026-07-28T00:00:00Z' },
      { id: 2, strategyId: 1, role: 'ai', content: '历史 AI 答', model: 'gpt-4o', createdAt: '2026-07-28T00:01:00Z' },
    ])
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => {
      expect(result.current.messages).toHaveLength(2)
      expect(result.current.messages[0].role).toBe('user')
      expect(result.current.messages[1].role).toBe('assistant')
    })
  })

  it('strategyId==null 不加载,保持欢迎语', async () => {
    const { result } = renderHook(() => useAssistantChat(null, [], { current: null }))
    expect(result.current.messages).toHaveLength(1)
    expect(result.current.messages[0].role).toBe('assistant')
    expect(result.current.model).toBe('')
    expect(mockFetchChatHistory).not.toHaveBeenCalled()
  })

  it('model 从 localStorage 读取(stored 在 availableModels 列表里 → 保留)', async () => {
    localStorage.setItem('ai-chat-model-1', 'deepseek-chat')
    const { result } = renderHook(() => useAssistantChat(1, ['deepseek-chat', 'deepseek-r1'], { current: null }))
    await waitFor(() => {
      expect(result.current.model).toBe('deepseek-chat')
    })
  })

  it('localStorage 陈旧 model(不在 availableModels)归零取首项', async () => {
    localStorage.setItem('ai-chat-model-1', 'gpt-4o') // 陈旧(不在 deepseek 列表)
    const { result } = renderHook(() => useAssistantChat(1, ['deepseek-v4', 'deepseek-r1'], { current: null }))
    await waitFor(() => {
      expect(result.current.model).toBe('deepseek-v4')
    })
    expect(localStorage.getItem('ai-chat-model-1')).toBe('deepseek-v4')
  })

  it('model 初值取 availableModels[0](无 localStorage)', async () => {
    const { result } = renderHook(() => useAssistantChat(1, ['gpt-5.6', 'gpt-5-mini'], { current: null }))
    await waitFor(() => {
      expect(result.current.model).toBe('gpt-5.6')
    })
  })

  it('model 留空时(availableModels 空)初始为空(后端用 adapter 默认)', async () => {
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => {
      expect(result.current.model).toBe('')
    })
  })

  it('onRun 带 model + body.messages 不含 role=ai(对齐 LLM 协议) + 含 assistant', async () => {
    localStorage.setItem('ai-chat-model-1', 'deepseek-chat')
    mockStreamChat.mockImplementation(() => Promise.resolve())
    const editorCodeRef = { current: 'print(1)' }
    const { result } = renderHook(() => useAssistantChat(1, ['deepseek-chat'], editorCodeRef))
    await waitFor(() => expect(result.current.model).toBe('deepseek-chat'))

    await act(async () => {
      result.current.onRun('帮我改进策略', 1)
    })

    const body = mockStreamChat.mock.calls[0][1] as {
      messages: { role: string; content: string }[]
      llmKeyId: number
      model: string
    }
    expect(body.llmKeyId).toBe(1)
    expect(body.model).toBe('deepseek-chat')
    // 回归:body.messages 不得含 role='ai'(后端 @Pattern ^(system|user|assistant)$ 会 400);
    // WELCOME/历史 AI 消息前端统一用 'assistant'
    expect(body.messages.some((m) => m.role === 'ai')).toBe(false)
    expect(body.messages.some((m) => m.role === 'assistant')).toBe(true)
    // body 不含 placeholder assistant(streaming 占位在 body snapshot 之后 append)
    expect(body.messages.some((m) => m.role === 'assistant' && m.content === '')).toBe(false)
  })

  it('onRun body 含 sourceCode(editorCodeRef.current) + codeSource=editor', async () => {
    mockStreamChat.mockImplementation(() => Promise.resolve())
    const editorCodeRef = { current: 'def f(): pass' }
    const { result } = renderHook(() => useAssistantChat(1, [], editorCodeRef))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.onRun('改这段', 1)
    })

    const body = mockStreamChat.mock.calls[0][1] as { sourceCode?: string; codeSource: string }
    expect(body.sourceCode).toBe('def f(): pass')
    expect(body.codeSource).toBe('EDITOR')
  })

  it('codeSource=DRAFT 时 body 不含 sourceCode(后端注入) + codeSource=DRAFT', async () => {
    mockStreamChat.mockImplementation(() => Promise.resolve())
    const editorCodeRef = { current: 'def f(): pass' } // 有 editor code 但 DRAFT 模式不传
    const { result } = renderHook(() => useAssistantChat(1, [], editorCodeRef))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.setCodeSource('DRAFT')
    })
    await act(async () => {
      result.current.onRun('改这段', 1)
    })

    const body = mockStreamChat.mock.calls[0][1] as { sourceCode?: string; codeSource: string }
    expect(body.sourceCode).toBeUndefined()
    expect(body.codeSource).toBe('DRAFT')
  })

  it('codeSource=PUBLISHED 时 body 不含 sourceCode + codeSource=PUBLISHED', async () => {
    mockStreamChat.mockImplementation(() => Promise.resolve())
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.setCodeSource('PUBLISHED')
    })
    await act(async () => {
      result.current.onRun('改这段', 1)
    })

    const body = mockStreamChat.mock.calls[0][1] as { sourceCode?: string; codeSource: string }
    expect(body.sourceCode).toBeUndefined()
    expect(body.codeSource).toBe('PUBLISHED')
  })

  it('onRun 无 llmKeyId 时 toast 警告,不调 streamChat', async () => {
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(mockFetchChatHistory).toHaveBeenCalled())

    await act(async () => {
      result.current.onRun('text', null)
    })

    expect(mockStreamChat).not.toHaveBeenCalled()
  })

  it('onChunk 累积到 messages 最后一条 assistant partial content + onClose saveAiMessage', async () => {
    localStorage.setItem('ai-chat-model-1', 'deepseek-chat')
    let handlers: { onChunk: (d: string) => void; onError: (d: string) => void; onClose: () => void }
    mockStreamChat.mockImplementation((_u, _b, _s, h) => {
      handlers = h
      return Promise.resolve()
    })
    const editorCodeRef = { current: 'print(1)' }
    const { result } = renderHook(() => useAssistantChat(1, ['deepseek-chat'], editorCodeRef))
    await waitFor(() => expect(result.current.model).toBe('deepseek-chat'))

    await act(async () => {
      result.current.onRun('帮我改进', 1)
    })
    // placeholder assistant(content='')已 append,messages 最后一条是空 assistant
    expect(result.current.isRunning).toBe(true)
    expect(result.current.messages.at(-1)).toMatchObject({ role: 'assistant', content: '' })

    // 模拟 streamText 累积(onChunk 调,更新最后一条 assistant content)
    await act(async () => {
      handlers!.onChunk('AI 建议把 ')
    })
    await act(async () => {
      handlers!.onChunk('ATR 改 2.0')
    })
    expect(result.current.messages.at(-1)).toMatchObject({
      role: 'assistant',
      content: 'AI 建议把 ATR 改 2.0',
    })

    // 模拟 onClose(SSE 流结束)
    await act(async () => {
      handlers!.onClose()
    })

    // saveAiMessage:保存 AI 回复(content=完整累积文本,model=本次用的)
    expect(mockSaveAiMessage).toHaveBeenCalledWith(1, {
      content: 'AI 建议把 ATR 改 2.0',
      model: 'deepseek-chat',
    })
    // isRunning 归零
    expect(result.current.isRunning).toBe(false)
  })

  it('onClose 空回复删 placeholder assistant(不留空气泡)', async () => {
    let handlers: { onChunk: (d: string) => void; onError: (d: string) => void; onClose: () => void }
    mockStreamChat.mockImplementation((_u, _b, _s, h) => {
      handlers = h
      return Promise.resolve()
    })
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.onRun('帮我', 1)
    })
    const lenBefore = result.current.messages.length
    // 无 onChunk,直接 onClose(空回复)
    await act(async () => {
      handlers!.onClose()
    })
    // placeholder 删了,messages 长度回到 onRun 前只多 user
    expect(result.current.messages.length).toBe(lenBefore - 1)
    expect(result.current.isRunning).toBe(false)
    // 空回复不存
    expect(mockSaveAiMessage).not.toHaveBeenCalled()
  })

  it('onCancel 归零 isRunning + 删空 placeholder(流式中停止)', async () => {
    // 模拟流式中(永不 resolve,onChunk/onClose 都不触发)
    mockStreamChat.mockImplementation(() => new Promise(() => {}))
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.onRun('帮我', 1)
    })
    expect(result.current.isRunning).toBe(true)
    // placeholder assistant append
    expect(result.current.messages.at(-1)).toMatchObject({ role: 'assistant', content: '' })

    await act(async () => {
      result.current.onCancel()
    })
    // isRunning 归零
    expect(result.current.isRunning).toBe(false)
    // 空 placeholder 删了(最后一条不是空 assistant)
    const last = result.current.messages.at(-1)
    expect(last?.content).not.toBe('')
  })

  it('finalizedRef 防 idle timeout 双错误(onError 后 onClose 不重复 toast/save)', async () => {
    let handlers: { onChunk: (d: string) => void; onError: (d: string) => void; onClose: () => void }
    mockStreamChat.mockImplementation((_u, _b, _s, h) => {
      handlers = h
      return Promise.resolve()
    })
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.onRun('帮我', 1)
    })
    await act(async () => {
      handlers!.onChunk('部分回复')
    })
    // onError 先(idle timeout 触发)
    await act(async () => {
      handlers!.onError('连接空闲超时')
    })
    expect(result.current.isRunning).toBe(false)
    // onClose 后触发(finalizedRef 应跳过,不再 saveAiMessage)
    await act(async () => {
      handlers!.onClose()
    })
    // 只存一次(或零次,onError 不存)— 关键是不双触发
    expect(mockSaveAiMessage).not.toHaveBeenCalled()
  })
})
