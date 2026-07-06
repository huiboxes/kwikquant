import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { apiFetch, ApiError } from './http'
import { useAuthStore } from '@/stores/authStore'

/**
 * http.ts envelope 解包测试。
 *
 * 重点:后端错误响应(401/5xx)按真实契约**不带 data 字段**
 * (如 401 {code:1001, message, traceId}),parseBody 必须按 code 抛错,
 * 不能因缺 data 字段把错误当成功返回(曾出现"随便输入都登录成功但不跳转"的 bug)。
 */
describe('apiFetch envelope 解包', () => {
  beforeEach(() => {
    useAuthStore.getState().clearAuth()
  })

  it('后端 401 错误响应(无 data 字段,真实后端契约)抛 ApiError,不当成功返回', async () => {
    server.use(
      http.post('/api/v1/test-401-no-data', () =>
        HttpResponse.json(
          { code: 1001, message: 'invalid credentials', traceId: 'abc' },
          { status: 401 },
        ),
      ),
    )
    await expect(
      apiFetch<{ accessToken: string }>('/api/v1/test-401-no-data', {
        method: 'POST',
        body: {},
        skipAuthRetry: true,
      }),
    ).rejects.toSatisfy((e: unknown) => {
      if (!(e instanceof ApiError)) return false
      return e.code === 1001 && e.message === 'invalid credentials' && e.status === 401
    })
  })

  it('成功 envelope(code=0) 解包 data', async () => {
    server.use(
      http.get('/api/v1/test-ok', () =>
        HttpResponse.json({ code: 0, message: 'ok', data: { foo: 'bar' } }),
      ),
    )
    const data = await apiFetch<{ foo: string }>('/api/v1/test-ok')
    expect(data).toEqual({ foo: 'bar' })
  })

  it('后端 5xx envelope(code!=0) 抛 ApiError', async () => {
    server.use(
      http.get('/api/v1/test-500', () =>
        HttpResponse.json(
          { code: 5001, message: 'server error', traceId: 'xyz' },
          { status: 500 },
        ),
      ),
    )
    await expect(apiFetch('/api/v1/test-500')).rejects.toMatchObject({
      code: 5001,
      message: 'server error',
    })
  })

  it('非 envelope 裸 body + HTTP 错误抛 ApiError(不被当成功)', async () => {
    server.use(
      http.get('/api/v1/test-bare-error', () =>
        HttpResponse.json(
          { timestamp: '2026-07-06', status: 500, error: 'Internal Server Error' },
          { status: 500 },
        ),
      ),
    )
    await expect(apiFetch('/api/v1/test-bare-error')).rejects.toBeInstanceOf(ApiError)
  })
})
