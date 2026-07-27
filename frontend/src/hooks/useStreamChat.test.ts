import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { useStreamChat } from './useStreamChat'

/**
 * useStreamChat 测试。覆盖进入策略加载历史(useEffect fetch)+ model localStorage 持久化。
 * send/onClose(SSE 流)需 mock streamChat,复杂留 follow-up;此 test 聚焦 useEffect 行为。
 */
describe('useStreamChat', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('进入 strategyId 加载历史(空则显欢迎语)', async () => {
    // MSW aiChatHandlers GET /strategies/1/ai/messages 初始返空 [] → useEffect fetch then setMessages(WELCOME)
    const { result } = renderHook(() => useStreamChat(1))

    // 初始 WELCOME(同步渲染),fetch 后仍 WELCOME(历史空)
    await waitFor(() => {
      expect(result.current.messages).toHaveLength(1)
      expect(result.current.messages[0].role).toBe('ai')
    })
  })

  it('strategyId==null 不加载,保持欢迎语', async () => {
    const { result } = renderHook(() => useStreamChat(null))
    expect(result.current.messages).toHaveLength(1)
    expect(result.current.messages[0].role).toBe('ai')
    expect(result.current.model).toBe('')
  })

  it('model 从 localStorage 读取(strategyId 变化时)', async () => {
    localStorage.setItem('ai-chat-model-1', 'deepseek-chat')
    const { result } = renderHook(() => useStreamChat(1))
    // useEffect fetch then setModel(localStorage.getItem)
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
})
