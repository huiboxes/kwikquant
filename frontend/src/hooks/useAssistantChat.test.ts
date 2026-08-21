import { renderHook, waitFor, act } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockStreamChat = vi.fn()
const mockFetchChatHistory = vi.fn()

vi.mock('@/lib/sse', () => ({
  streamChat: (...args: unknown[]) => mockStreamChat(...args),
}))
vi.mock('@/api/ai', () => ({
  AI_CHAT_URL: '/api/v1/ai/chat',
  fetchChatHistory: (...args: unknown[]) => mockFetchChatHistory(...args),
}))
vi.mock('sonner', () => ({ toast: { warning: vi.fn(), error: vi.fn() } }))

import { useAssistantChat } from './useAssistantChat'

/**
 * useAssistantChat 测试(自建 ChatThread state 层，rAF 批处理)。
 *
 * rAF mock:收集 callback,flushRafs() 手动触发(测试批处理：多 onChunk 同帧一次 flush)。
 * streamChat/fetchChatHistory 全 vi.mock，零真实网络/SSE。
 * 持久化(user 消息 + assistant 回复)全部由后端完成，前端 hook 不触发保存调用。
 */

let rafCallbacks: Array<() => void> = []

beforeEach(() => {
  rafCallbacks = []
  vi.stubGlobal('requestAnimationFrame', (cb: () => void) => {
    rafCallbacks.push(cb)
    return rafCallbacks.length
  })
  vi.stubGlobal('cancelAnimationFrame', () => {
    // 测试简化：不精确移除(已 cancel 的 cb 执行时 buffer 空，flushNow no-op)
  })
})

function flushRafs() {
  const cbs = rafCallbacks
  rafCallbacks = []
  cbs.forEach((cb) => cb())
}

describe('useAssistantChat', () => {
  beforeEach(() => {
    localStorage.clear()
    mockStreamChat.mockReset()
    mockFetchChatHistory.mockReset().mockResolvedValue([])
  })

  it('进入 strategyId 加载历史(空则 messages=[])', async () => {
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => {
      expect(mockFetchChatHistory).toHaveBeenCalledWith(1)
      expect(result.current.messages).toHaveLength(0)
    })
  })

  it('历史消息 role 直用：后端已统一 user/assistant(Wave 1.3 V48 迁移后无 ai)', async () => {
    mockFetchChatHistory.mockResolvedValueOnce([
      { id: 1, strategyId: 1, role: 'user', content: '历史用户问', model: null, createdAt: '2026-07-28T00:00:00Z' },
      { id: 2, strategyId: 1, role: 'assistant', content: '历史 AI 答', model: 'gpt-4o', createdAt: '2026-07-28T00:01:00Z' },
    ])
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => {
      expect(result.current.messages).toHaveLength(2)
      expect(result.current.messages[0].role).toBe('user')
      expect(result.current.messages[1].role).toBe('assistant')
    })
  })

  it('strategyId==null 不加载，messages=[]', async () => {
    const { result } = renderHook(() => useAssistantChat(null, [], { current: null }))
    expect(result.current.messages).toHaveLength(0)
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
    localStorage.setItem('ai-chat-model-1', 'gpt-4o')
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

  it('model 留空时(availableModels 空)初始为空', async () => {
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => {
      expect(result.current.model).toBe('')
    })
  })

  it('onRun 带 model + body.messages 不含 role=ai + 含本次 user + 不含 placeholder', async () => {
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
    expect(body.messages.some((m) => m.role === 'ai')).toBe(false)
    expect(body.messages).toHaveLength(1)
    expect(body.messages[0].role).toBe('user')
    expect(body.messages[0].content).toBe('帮我改进策略')
    expect(body.messages.some((m) => m.role === 'assistant' && m.content === '')).toBe(false)
  })

  it('onRun body 含 sourceCode(EDITOR) + codeSource=EDITOR', async () => {
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

  it('codeSource=DRAFT 时 body 不含 sourceCode + codeSource=DRAFT', async () => {
    mockStreamChat.mockImplementation(() => Promise.resolve())
    const editorCodeRef = { current: 'def f(): pass' }
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

  it('onRun 无 llmKeyId 时 toast 警告，不调 streamChat', async () => {
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(mockFetchChatHistory).toHaveBeenCalled())

    await act(async () => {
      result.current.onRun('text', null)
    })

    expect(mockStreamChat).not.toHaveBeenCalled()
  })

  it('onChunk rAF 批处理：累积到 last assistant content(flushRafs 后) + onClose 归零 isRunning', async () => {
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
    expect(result.current.isRunning).toBe(true)
    expect(result.current.messages.at(-1)).toMatchObject({ role: 'assistant', content: '' })

    // onChunk → buffer,rAF scheduled(未 flush 前 content 仍空)
    await act(async () => {
      handlers!.onChunk('AI 建议把 ')
    })
    expect(result.current.messages.at(-1)?.content).toBe('')
    await act(async () => {
      flushRafs()
    })
    expect(result.current.messages.at(-1)?.content).toBe('AI 建议把 ')

    // 两次 onChunk 同帧 → 一次 flush 合并(批处理关键：多 chunk 一次 setMessages)
    await act(async () => {
      handlers!.onChunk('ATR ')
      handlers!.onChunk('改 2.0')
    })
    expect(result.current.messages.at(-1)?.content).toBe('AI 建议把 ')
    await act(async () => {
      flushRafs()
    })
    expect(result.current.messages.at(-1)?.content).toBe('AI 建议把 ATR 改 2.0')

    await act(async () => {
      handlers!.onClose()
    })
    expect(result.current.messages.at(-1)?.content).toBe('AI 建议把 ATR 改 2.0')
    // assistant 回复由后端流正常结束时落库，前端 onClose 不触发保存调用
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
    await act(async () => {
      handlers!.onClose()
    })
    expect(result.current.messages.length).toBe(lenBefore - 1)
    expect(result.current.isRunning).toBe(false)
  })

  it('onCancel 归零 isRunning + 删空 placeholder', async () => {
    mockStreamChat.mockImplementation(() => new Promise(() => {}))
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.onRun('帮我', 1)
    })
    expect(result.current.isRunning).toBe(true)
    expect(result.current.messages.at(-1)).toMatchObject({ role: 'assistant', content: '' })

    await act(async () => {
      result.current.onCancel()
    })
    expect(result.current.isRunning).toBe(false)
    const last = result.current.messages.at(-1)
    expect(last?.content).not.toBe('')
  })

  it('finalizedRef 防 onError/onClose 双触发(onError setLastError 保留 partial,onClose 跳过)', async () => {
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
    await act(async () => {
      flushRafs()
    })
    expect(result.current.messages.at(-1)?.content).toBe('部分回复')

    // onError → setLastError(保留 partial content + 标记 error)
    await act(async () => {
      handlers!.onError('连接空闲超时')
    })
    expect(result.current.isRunning).toBe(false)
    expect(result.current.messages.at(-1)?.error).toBe('连接空闲超时')
    expect(result.current.messages.at(-1)?.content).toBe('部分回复')
    // onClose 后触发(finalizedRef 跳过，不再定稿)
    await act(async () => {
      handlers!.onClose()
    })
    expect(result.current.messages.at(-1)?.error).toBe('连接空闲超时')
    expect(result.current.messages.at(-1)?.content).toBe('部分回复')
  })

  it('StoreMessage 有稳定唯一 id', async () => {
    mockStreamChat.mockImplementation(() => Promise.resolve())
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.onRun('帮我', 1)
    })
    expect(result.current.messages.length).toBeGreaterThanOrEqual(2)
    for (const m of result.current.messages) {
      expect(typeof m.id).toBe('string')
      expect(m.id.length).toBeGreaterThan(0)
    }
    const ids = result.current.messages.map((m) => m.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('loadHistory 用 db id 作 cache key(稳定，刷新不变)', async () => {
    mockFetchChatHistory.mockResolvedValueOnce([
      { id: 101, strategyId: 1, role: 'user', content: '历史问', model: null, createdAt: '2026-07-28T00:00:00Z' },
    ])
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.messages).toHaveLength(1))
    expect(result.current.messages[0].id).toBe('101')
  })

  it('retryLast 删 last error assistant + 用 last user 重新请求', async () => {
    let handlers: { onChunk: (d: string) => void; onError: (d: string) => void; onClose: () => void }
    mockStreamChat.mockImplementation((_u, _b, _s, h) => {
      handlers = h
      return Promise.resolve()
    })
    const { result } = renderHook(() => useAssistantChat(1, [], { current: null }))
    await waitFor(() => expect(result.current.isRunning).toBe(false))

    await act(async () => {
      result.current.onRun('帮我改进', 1)
    })
    await act(async () => {
      handlers!.onError('超时')
    })
    // last assistant 有 error
    expect(result.current.messages.at(-1)?.error).toBe('超时')
    const lenBeforeRetry = result.current.messages.length

    // retryLast → 删 last error assistant + 新 placeholder + 重发 streamChat
    mockStreamChat.mockClear()
    await act(async () => {
      result.current.retryLast()
    })
    // 长度：删 1 error assistant + 加 1 placeholder = 不变
    expect(result.current.messages.length).toBe(lenBeforeRetry)
    expect(result.current.messages.at(-1)?.error).toBeUndefined()
    expect(result.current.messages.at(-1)?.role).toBe('assistant')
    expect(mockStreamChat).toHaveBeenCalledTimes(1)
    // body.messages 含 last user(复用 context)，不含 placeholder
    const body = mockStreamChat.mock.calls[0][1] as { messages: { role: string }[] }
    expect(body.messages.some((m) => m.role === 'user')).toBe(true)
    expect(body.messages.some((m) => m.role === 'assistant')).toBe(false)
  })

  it('retryLast 非错误态不重试(last 非 error assistant → no-op)', async () => {
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
    // onClose 空回复 → placeholder 删，只剩 user
    await act(async () => {
      handlers!.onClose()
    })
    // last 是 user,retryLast no-op
    mockStreamChat.mockClear()
    await act(async () => {
      result.current.retryLast()
    })
    expect(mockStreamChat).not.toHaveBeenCalled()
  })
})
