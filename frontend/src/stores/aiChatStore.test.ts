import { describe, it, expect, beforeEach } from 'vitest'
import { useAiChatStore } from './aiChatStore'

describe('aiChatStore', () => {
  beforeEach(() => {
    useAiChatStore.getState().clear()
    useAiChatStore.setState({ llmKeyId: null })
  })

  it('addUserMessage 追加 user 消息', () => {
    useAiChatStore.getState().addUserMessage('你好')
    expect(useAiChatStore.getState().messages).toHaveLength(1)
    expect(useAiChatStore.getState().messages[0]).toEqual({ role: 'user', content: '你好' })
  })

  it('startAssistant 追加空 assistant + streaming=true', () => {
    useAiChatStore.getState().startAssistant()
    const s = useAiChatStore.getState()
    expect(s.streaming).toBe(true)
    expect(s.messages.at(-1)).toEqual({ role: 'assistant', content: '' })
  })

  it('appendToLastAssistant 累积 chunk 到最后 assistant', () => {
    useAiChatStore.getState().startAssistant()
    useAiChatStore.getState().appendToLastAssistant('你')
    useAiChatStore.getState().appendToLastAssistant('好')
    useAiChatStore.getState().appendToLastAssistant('世界')
    expect(useAiChatStore.getState().messages.at(-1)?.content).toBe('你好世界')
  })

  it('appendToLastAssistant 无 assistant 时 noop', () => {
    useAiChatStore.getState().appendToLastAssistant('x')
    expect(useAiChatStore.getState().messages).toHaveLength(0)
  })

  it('markLastAssistantError 替换空 assistant 内容', () => {
    useAiChatStore.getState().startAssistant()
    useAiChatStore.getState().markLastAssistantError('余额不足')
    expect(useAiChatStore.getState().messages.at(-1)?.content).toBe('[错误] 余额不足')
  })

  it('markLastAssistantError 已有内容时不覆盖', () => {
    useAiChatStore.getState().startAssistant()
    useAiChatStore.getState().appendToLastAssistant('部分回复')
    useAiChatStore.getState().markLastAssistantError('断连')
    expect(useAiChatStore.getState().messages.at(-1)?.content).toBe('部分回复')
  })

  it('clear 清空 messages + streaming', () => {
    useAiChatStore.getState().addUserMessage('x')
    useAiChatStore.getState().setStreaming(true)
    useAiChatStore.getState().clear()
    expect(useAiChatStore.getState().messages).toHaveLength(0)
    expect(useAiChatStore.getState().streaming).toBe(false)
  })

  it('setLlmKeyId', () => {
    useAiChatStore.getState().setLlmKeyId(42)
    expect(useAiChatStore.getState().llmKeyId).toBe(42)
  })
})
