import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { exportTradeHistory } from './trade-history'
import { useAuthStore } from '@/stores/authStore'
import { server } from '@/test/server'

describe('exportTradeHistory', () => {
  // setState 直接写 accessToken,绕过 setAccessToken 内部 decodeJwt(无需造合法 JWT)
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'test-token',
      user: { userId: 1, username: 'tester' },
    })
  })

  it('带 Bearer + 解析 Content-Disposition 文件名', async () => {
    let capturedAuth: string | null = null
    server.use(
      http.get('/api/v1/trade-history/export', ({ request }) => {
        capturedAuth = request.headers.get('Authorization')
        return new HttpResponse("csv,data", {
          status: 200,
          headers: { 'content-disposition': 'attachment; filename="th.csv"' },
        })
      }),
    )
    const r = await exportTradeHistory({ format: 'csv' })
    expect(r.filename).toBe('th.csv')
    expect(r.blob.size).toBe(8) // 'csv,data'
    expect(capturedAuth).toBe('Bearer test-token') // authFetch 带了 Bearer(auth 接入核心)
  })

  it('无 Content-Disposition → filename null(调用方兜默认名)', async () => {
    server.use(
      http.get('/api/v1/trade-history/export', () =>
        new HttpResponse("x", { status: 200 }),
      ),
    )
    const r = await exportTradeHistory({ format: 'json' })
    expect(r.filename).toBeNull()
    expect(r.blob.size).toBe(1) // 'x'
  })

  it('非 200 抛错', async () => {
    server.use(
      http.get('/api/v1/trade-history/export', () =>
        new HttpResponse(null, { status: 500 }),
      ),
    )
    await expect(exportTradeHistory({ format: 'csv' })).rejects.toThrow(
      'export failed: 500',
    )
  })
})
