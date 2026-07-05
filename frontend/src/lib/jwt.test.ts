import { describe, it, expect } from 'vitest'
import { decodeJwt, isExpired, InvalidTokenError } from './jwt'

/** 生成 mock JWT:header.payload.signature,payload 是 base64url 编码的 JSON */
function mockJwt(payload: Record<string, unknown>): string {
  const header = { alg: 'HS256', typ: 'JWT' }
  const b64url = (obj: unknown) => {
    const json = JSON.stringify(obj)
    const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(json)))
    return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  }
  return `${b64url(header)}.${b64url(payload)}.signature`
}

describe('decodeJwt', () => {
  it('正确 decode sub(username) + username + exp', () => {
    const token = mockJwt({ sub: '42', username: 'alice', exp: 1800000000 })
    const payload = decodeJwt(token)
    expect(payload.userId).toBe(42)
    expect(payload.username).toBe('alice')
    expect(payload.exp).toBe(1800000000)
  })

  it('sub 为数字时也正确 parseLong', () => {
    const token = mockJwt({ sub: 100, username: 'bob', exp: 1800000000 })
    expect(decodeJwt(token).userId).toBe(100)
  })

  it('可选 iat/jti 字段透传', () => {
    const token = mockJwt({ sub: '1', username: 'x', exp: 1800000000, iat: 1700000000, jti: 'abc' })
    const payload = decodeJwt(token)
    expect(payload.iat).toBe(1700000000)
    expect(payload.jti).toBe('abc')
  })

  it('非 JWT 格式(段数不足)抛 InvalidTokenError', () => {
    expect(() => decodeJwt('not-a-jwt')).toThrow(InvalidTokenError)
    expect(() => decodeJwt('a.b')).toThrow(InvalidTokenError)
    expect(() => decodeJwt('a.b.c.d')).toThrow(InvalidTokenError)
  })

  it('payload 非法 JSON 抛 InvalidTokenError', () => {
    const bad = `header.${btoa('not json').replace(/=/g, '')}.sig`
    expect(() => decodeJwt(bad)).toThrow(InvalidTokenError)
  })

  it('缺 sub 抛错', () => {
    const token = mockJwt({ username: 'x', exp: 1800000000 })
    expect(() => decodeJwt(token)).toThrow(/sub/i)
  })

  it('缺 username 抛错', () => {
    const token = mockJwt({ sub: '1', exp: 1800000000 })
    expect(() => decodeJwt(token)).toThrow(/username/i)
  })

  it('缺 exp 抛错', () => {
    const token = mockJwt({ sub: '1', username: 'x' })
    expect(() => decodeJwt(token)).toThrow(/exp/i)
  })

  it('sub 非法(0 或负数)抛错', () => {
    const token = mockJwt({ sub: '0', username: 'x', exp: 1800000000 })
    expect(() => decodeJwt(token)).toThrow(/userId/)
  })

  it('含 UTF-8 多字节字符的 username 正确 decode', () => {
    const token = mockJwt({ sub: '1', username: '中文用户🎯', exp: 1800000000 })
    expect(decodeJwt(token).username).toBe('中文用户🎯')
  })
})

describe('isExpired', () => {
  it('未来 exp 未过期', () => {
    const future = Math.floor(Date.now() / 1000) + 3600
    expect(isExpired(future)).toBe(false)
  })

  it('过去 exp 已过期', () => {
    const past = Math.floor(Date.now() / 1000) - 100
    expect(isExpired(past)).toBe(true)
  })

  it('边界:刚到 exp 视为已过期', () => {
    const now = Date.now()
    const exp = Math.floor(now / 1000)
    expect(isExpired(exp, now)).toBe(true)
  })

  it('支持自定义 nowMs', () => {
    const exp = 1000
    expect(isExpired(exp, 5_000_000)).toBe(true)
    expect(isExpired(exp, 100)).toBe(false)
  })
})
