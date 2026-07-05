import { describe, it, expect } from 'vitest'
import { aiChatSchema, chatMessageSchema } from './ai-chat'

describe('chatMessageSchema', () => {
  it('合法 role + content 通过', () => {
    const r = chatMessageSchema.safeParse({ role: 'user', content: '帮我写一个网格策略' })
    expect(r.success).toBe(true)
  })

  it('role 非法(system/user/assistant 之外)失败', () => {
    const r = chatMessageSchema.safeParse({ role: 'tool', content: 'x' })
    expect(r.success).toBe(false)
  })

  it('content 空字符串失败', () => {
    const r = chatMessageSchema.safeParse({ role: 'user', content: '' })
    expect(r.success).toBe(false)
  })

  it('content > 8000 字符失败', () => {
    const r = chatMessageSchema.safeParse({ role: 'user', content: 'a'.repeat(8001) })
    expect(r.success).toBe(false)
  })
})

describe('aiChatSchema', () => {
  const validBase = {
    llmKeyId: 1,
    messages: [{ role: 'user' as const, content: 'hi' }],
    strategyId: 1,
  }

  it('合法请求通过', () => {
    const r = aiChatSchema.safeParse(validBase)
    expect(r.success).toBe(true)
  })

  it('messages > 100 条失败', () => {
    const messages = Array.from({ length: 101 }, () => ({ role: 'user' as const, content: 'x' }))
    const r = aiChatSchema.safeParse({ ...validBase, messages })
    expect(r.success).toBe(false)
  })

  it('llmKeyId 非正数失败', () => {
    const r = aiChatSchema.safeParse({ ...validBase, llmKeyId: 0 })
    expect(r.success).toBe(false)
  })

  it('strategyId 缺失失败(前端必传)', () => {
    const rest: Partial<typeof validBase> = { ...validBase }
    delete rest.strategyId
    const r = aiChatSchema.safeParse(rest)
    expect(r.success).toBe(false)
  })

  it('temperature 超出 0-2 失败', () => {
    const r = aiChatSchema.safeParse({ ...validBase, temperature: 3 })
    expect(r.success).toBe(false)
  })

  it('maxTokens > 32768 失败', () => {
    const r = aiChatSchema.safeParse({ ...validBase, maxTokens: 40000 })
    expect(r.success).toBe(false)
  })

  it('model/temperature/maxTokens 可选不传通过', () => {
    const r = aiChatSchema.safeParse(validBase)
    expect(r.success).toBe(true)
  })
})
