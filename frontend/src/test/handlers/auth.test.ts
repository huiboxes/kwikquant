import { describe, it, expect } from 'vitest'
import { apiFetch } from '@/lib/http'

describe('auth handlers', () => {
  it('login 成功返 token envelope', async () => {
    const res = await apiFetch<{ accessToken: string; expiresIn: number }>('/api/v1/auth/login', { method: 'POST', body: { username: 'demo', password: 'pass1234' }, skipAuthRetry: true })
    expect(res.accessToken).toMatch(/^test-access-token\./)
    expect(res.expiresIn).toBeGreaterThan(0)
  })
  it('login 密码错返 401 + code 1001', async () => {
    await expect(apiFetch('/api/v1/auth/login', { method: 'POST', body: { username: 'demo', password: 'wrong' }, skipAuthRetry: true }))
      .rejects.toMatchObject({ code: 1001, status: 401 })
  })
  it('register 缺邀请码返 400 + code 3002', async () => {
    await expect(apiFetch('/api/v1/auth/register', { method: 'POST', body: { username: 'new', email: 'n@e.com', password: 'pass1234', inviteCode: 'BAD' }, skipAuthRetry: true }))
      .rejects.toMatchObject({ code: 3002, status: 400 })
  })
  it('refresh 成功返新 token', async () => {
    const res = await apiFetch<{ accessToken: string; expiresIn: number }>('/api/v1/auth/refresh', { method: 'POST', skipAuthRetry: true })
    expect(res.accessToken).toMatch(/^test-access-token\./)
  })
  it('logout 返 code 0', async () => {
    // apiFetch<T> 返回 envelope 拆开后的 data(code===0 时),logout handler 返 envelope(null) → data=null
    await expect(apiFetch('/api/v1/auth/logout', { method: 'POST', skipAuthRetry: true })).resolves.toBe(null)
  })
})
