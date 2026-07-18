import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { resetPaperAccount } from './account'
import { useAuthStore } from '@/stores/authStore'
import { server } from '@/test/server'

describe('resetPaperAccount', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'test-token',
      user: { userId: 1, username: 'tester' },
    })
  })

  it('带 Bearer + POST /accounts/{id}/paper/reset 200 成功', async () => {
    let capturedAuth: string | null = null
    let capturedUrl = ''
    server.use(
      http.post('/api/v1/accounts/:id/paper/reset', ({ request }) => {
        capturedAuth = request.headers.get('Authorization')
        capturedUrl = request.url
        return HttpResponse.json(
          { code: 0, message: 'ok', data: { accountId: 1 }, traceId: 't3' },
          { status: 200 },
        )
      }),
    )
    const r = await resetPaperAccount(1)
    expect(capturedUrl).toContain('/accounts/1/paper/reset')
    expect(capturedAuth).toBe('Bearer test-token')
    expect(r).toBeTruthy() // apiFetch unwrap envelope:200 返 ResetResult data
    expect((r as { accountId?: number }).accountId).toBe(1)
  })

  it('非 PAPER 账户 400(7001)抛错', async () => {
    server.use(
      http.post('/api/v1/accounts/:id/paper/reset', () =>
        HttpResponse.json(
          { code: 7001, message: '非 PAPER 账户', data: null, traceId: 't4' },
          { status: 400 },
        ),
      ),
    )
    await expect(resetPaperAccount(2)).rejects.toThrow()
  })
})
