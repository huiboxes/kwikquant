import { describe, it, expect, beforeEach } from 'vitest'
import { useAuthStore } from './authStore'

/** 生成真实可 decode 的 mock JWT */
function mockJwt(payload: Record<string, unknown>): string {
  const header = { alg: 'HS256', typ: 'JWT' }
  const b64url = (obj: unknown) => {
    const json = JSON.stringify(obj)
    const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(json)))
    return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  }
  return `${b64url(header)}.${b64url(payload)}.signature`
}

const FUTURE_EXP = Math.floor(Date.now() / 1000) + 3600
const PAST_EXP = Math.floor(Date.now() / 1000) - 100

describe('authStore 三态机', () => {
  beforeEach(() => {
    useAuthStore.setState({ status: 'unknown', accessToken: null, user: null })
  })

  it('初始状态为 unknown', () => {
    expect(useAuthStore.getState().status).toBe('unknown')
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })

  it('setAccessToken(decode 成功 + 未过期) → authenticated + user 派生', () => {
    useAuthStore.getState().setAccessToken(mockJwt({ sub: '42', username: 'alice', exp: FUTURE_EXP }))
    const s = useAuthStore.getState()
    expect(s.status).toBe('authenticated')
    expect(s.accessToken).toBeTruthy()
    expect(s.user).toEqual({ userId: 42, username: 'alice' })
  })

  it('setAccessToken(token 过期) → anonymous,不存 token', () => {
    useAuthStore.getState().setAccessToken(mockJwt({ sub: '1', username: 'x', exp: PAST_EXP }))
    expect(useAuthStore.getState().status).toBe('anonymous')
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('setAccessToken(非法 token) → anonymous', () => {
    useAuthStore.getState().setAccessToken('not-a-jwt')
    expect(useAuthStore.getState().status).toBe('anonymous')
  })

  it('clearAuth → anonymous + 清空 token/user', () => {
    useAuthStore.getState().setAccessToken(mockJwt({ sub: '1', username: 'x', exp: FUTURE_EXP }))
    useAuthStore.getState().clearAuth()
    const s = useAuthStore.getState()
    expect(s.status).toBe('anonymous')
    expect(s.accessToken).toBeNull()
    expect(s.user).toBeNull()
  })

  it('hydrate:无 token → anonymous', () => {
    useAuthStore.getState().hydrate()
    expect(useAuthStore.getState().status).toBe('anonymous')
  })

  it('hydrate:有未过期 token → authenticated', () => {
    useAuthStore.setState({ accessToken: mockJwt({ sub: '5', username: 'bob', exp: FUTURE_EXP }) })
    useAuthStore.getState().hydrate()
    expect(useAuthStore.getState().status).toBe('authenticated')
    expect(useAuthStore.getState().user).toEqual({ userId: 5, username: 'bob' })
  })

  it('hydrate:有过期 token → anonymous + 清空', () => {
    useAuthStore.setState({ accessToken: mockJwt({ sub: '5', username: 'bob', exp: PAST_EXP }) })
    useAuthStore.getState().hydrate()
    expect(useAuthStore.getState().status).toBe('anonymous')
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('状态转换:unknown → authenticated → anonymous(401 失败)', () => {
    useAuthStore.getState().setAccessToken(mockJwt({ sub: '1', username: 'x', exp: FUTURE_EXP }))
    expect(useAuthStore.getState().status).toBe('authenticated')
    useAuthStore.getState().clearAuth()
    expect(useAuthStore.getState().status).toBe('anonymous')
  })
})
