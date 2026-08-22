import { describe, it, expect } from 'vitest'
import { sanitizeRedirectTarget, authUrlWithFrom, loginUrlFor } from './redirect'

describe('sanitizeRedirectTarget', () => {
  it('合法站内路径放行（含 query）', () => {
    expect(sanitizeRedirectTarget('/trade')).toBe('/trade')
    expect(sanitizeRedirectTarget('/strategy?taskId=3&retry=1')).toBe('/strategy?taskId=3&retry=1')
    expect(sanitizeRedirectTarget('/')).toBe('/')
  })

  it('空/缺失返 null', () => {
    expect(sanitizeRedirectTarget(null)).toBeNull()
    expect(sanitizeRedirectTarget(undefined)).toBeNull()
    expect(sanitizeRedirectTarget('')).toBeNull()
  })

  it('拒绝绝对 URL 与 protocol-relative 变体（开放重定向）', () => {
    expect(sanitizeRedirectTarget('https://evil.com')).toBeNull()
    expect(sanitizeRedirectTarget('//evil.com')).toBeNull()
    expect(sanitizeRedirectTarget('/\\evil.com')).toBeNull()
  })

  it('拒绝认证页自身的全部变体（防循环，对齐 react-router 大小写不敏感匹配）', () => {
    expect(sanitizeRedirectTarget('/login')).toBeNull()
    expect(sanitizeRedirectTarget('/register')).toBeNull()
    expect(sanitizeRedirectTarget('/login?from=/trade')).toBeNull()
    expect(sanitizeRedirectTarget('/login#foo')).toBeNull()
    expect(sanitizeRedirectTarget('/login/')).toBeNull()
    expect(sanitizeRedirectTarget('/LOGIN')).toBeNull()
    expect(sanitizeRedirectTarget('/Register/')).toBeNull()
    // /settings?tab=accounts 等非认证页不受影响
    expect(sanitizeRedirectTarget('/settings?tab=accounts')).toBe('/settings?tab=accounts')
  })

  it('拒绝含控制字符的输入（CRLF 注入变体）', () => {
    expect(sanitizeRedirectTarget('/\n/evil.com')).toBeNull()
    expect(sanitizeRedirectTarget('/trade\r\n')).toBeNull()
    expect(sanitizeRedirectTarget('/tr\tade')).toBeNull()
  })
})

describe('authUrlWithFrom', () => {
  it('合法目标拼接编码后的 from', () => {
    expect(authUrlWithFrom('/register', '/strategy?taskId=3')).toBe(
      '/register?from=%2Fstrategy%3FtaskId%3D3',
    )
  })

  it('非法/空目标返回裸路径', () => {
    expect(authUrlWithFrom('/login', null)).toBe('/login')
    expect(authUrlWithFrom('/login', '//evil.com')).toBe('/login')
  })
})

describe('loginUrlFor', () => {
  it('守卫写侧：pathname + search 编码进 from', () => {
    expect(loginUrlFor({ pathname: '/strategy', search: '?taskId=3&retry=1' })).toBe(
      '/login?from=%2Fstrategy%3FtaskId%3D3%26retry%3D1',
    )
  })

  it('无 search 时仅编码 pathname', () => {
    expect(loginUrlFor({ pathname: '/trade', search: '' })).toBe('/login?from=%2Ftrade')
  })
})
