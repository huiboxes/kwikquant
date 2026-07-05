import { describe, it, expect, beforeEach } from 'vitest'
import { streamChat } from './sse'
import { ApiError } from './http'
import { useAuthStore } from '@/stores/authStore'

function makeHandlers() {
  const chunks: string[] = []
  const errors: string[] = []
  let closed = false
  return {
    chunks,
    errors,
    get closed(): boolean {
      return closed
    },
    onChunk: (data: string) => chunks.push(data),
    onError: (data: string) => errors.push(data),
    onClose: () => {
      closed = true
    },
  }
}

describe('streamChat (MSW 集成)', () => {
  beforeEach(() => {
    // streamChat 不走 apiFetch,无 token 也发(不带 Authorization),MSW 不验 token
    useAuthStore.getState().clearAuth()
  })

  it('正常流:chunk1 + chunk2 + done → onChunk ×2 + onClose', async () => {
    const h = makeHandlers()
    const controller = new AbortController()
    await streamChat(
      '/api/v1/ai/chat',
      { llmKeyId: 1, messages: [{ role: 'user', content: 'hi' }], strategyId: 1 },
      controller.signal,
      h,
    )
    expect(h.chunks).toEqual(['你好', '世界'])
    expect(h.errors).toHaveLength(0)
    expect(h.closed).toBe(true)
  })

  it('4001 key 不存在抛 ApiError(4001)', async () => {
    const h = makeHandlers()
    const controller = new AbortController()
    await expect(
      streamChat(
        '/api/v1/ai/chat',
        { llmKeyId: 9999, messages: [], strategyId: 1 },
        controller.signal,
        h,
      ),
    ).rejects.toMatchObject({ code: 4001 })
  })

  it('403 key 非本人抛 ApiError(4003)', async () => {
    const controller = new AbortController()
    await expect(
      streamChat(
        '/api/v1/ai/chat',
        { llmKeyId: 8888, messages: [], strategyId: 1 },
        controller.signal,
        makeHandlers(),
      ),
    ).rejects.toMatchObject({ code: 4003 })
  })

  it('500 INVALID_PROVIDER 抛 ApiError(8002)', async () => {
    const controller = new AbortController()
    await expect(
      streamChat(
        '/api/v1/ai/chat',
        { llmKeyId: 8002, messages: [], strategyId: 1 },
        controller.signal,
        makeHandlers(),
      ),
    ).rejects.toMatchObject({ code: 8002 })
  })

  it('502 PROVIDER_ERROR 抛 ApiError(8003)', async () => {
    const controller = new AbortController()
    await expect(
      streamChat(
        '/api/v1/ai/chat',
        { llmKeyId: 8003, messages: [], strategyId: 1 },
        controller.signal,
        makeHandlers(),
      ),
    ).rejects.toMatchObject({ code: 8003 })
  })

  it('401 未认证抛 ApiError(1001,不重放)', async () => {
    const controller = new AbortController()
    await expect(
      streamChat(
        '/api/v1/ai/chat',
        { llmKeyId: 1001, messages: [], strategyId: 1 },
        controller.signal,
        makeHandlers(),
      ),
    ).rejects.toBeInstanceOf(ApiError)
  })

  it('Stop(AbortController.abort)静默返回,不抛', async () => {
    const h = makeHandlers()
    const controller = new AbortController()
    const promise = streamChat(
      '/api/v1/ai/chat',
      { llmKeyId: 1, messages: [{ role: 'user', content: 'hi' }], strategyId: 1 },
      controller.signal,
      h,
    )
    controller.abort()
    await expect(promise).resolves.toBeUndefined()
  })
})
