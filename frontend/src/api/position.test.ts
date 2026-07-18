import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { closePosition } from './position'
import { useAuthStore } from '@/stores/authStore'
import { server } from '@/test/server'

describe('closePosition', () => {
  // setState 直接写 accessToken,绕过 setAccessToken 内部 decodeJwt(无需造合法 JWT)
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'test-token',
      user: { userId: 1, username: 'tester' },
    })
  })

  it('带 Bearer + POST /positions/{id}/close 200 成功', async () => {
    let capturedAuth: string | null = null
    let capturedUrl = ''
    server.use(
      http.post('/api/v1/positions/:positionId/close', ({ request }) => {
        capturedAuth = request.headers.get('Authorization')
        capturedUrl = request.url
        return HttpResponse.json(
          { code: 0, message: 'ok', data: null, traceId: 't1' },
          { status: 200 },
        )
      }),
    )
    const r = await closePosition(128)
    expect(capturedUrl).toContain('/positions/128/close')
    expect(capturedAuth).toBe('Bearer test-token')
    expect(r).toBeNull() // apiFetch unwrap envelope:200 成功 data=null
  })

  it('FLAT/不存在 404(4001)抛错', async () => {
    server.use(
      http.post('/api/v1/positions/:positionId/close', () =>
        HttpResponse.json(
          { code: 4001, message: '持仓不存在', data: null, traceId: 't2' },
          { status: 404 },
        ),
      ),
    )
    await expect(closePosition(999)).rejects.toThrow()
  })
})
