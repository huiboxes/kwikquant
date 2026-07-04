import { describe, expect, it } from 'vitest'
import { defaultRetry } from './query-options'

describe('query-options.defaultRetry', () => {
  it('status=401 → 不重试（不管 failureCount）', () => {
    expect(defaultRetry(0, { status: 401 })).toBe(false)
    expect(defaultRetry(5, { status: 401 })).toBe(false)
  })
  it('status=403 → 不重试', () => {
    expect(defaultRetry(0, { status: 403 })).toBe(false)
  })
  it('status=500 + failureCount=0 → 重试', () => {
    expect(defaultRetry(0, { status: 500 })).toBe(true)
  })
  it('status=500 + failureCount=1 → 重试（第二次）', () => {
    expect(defaultRetry(1, { status: 500 })).toBe(true)
  })
  it('status=500 + failureCount=2 → 不重试（上限）', () => {
    expect(defaultRetry(2, { status: 500 })).toBe(false)
  })
  it('未知形状（无 status 属性）+ failureCount=0 → 重试', () => {
    expect(defaultRetry(0, new Error('network'))).toBe(true)
  })
  it('null 错误 → 重试（duck-typing 安全）', () => {
    expect(defaultRetry(0, null)).toBe(true)
  })
  it('undefined 错误 → 重试', () => {
    expect(defaultRetry(0, undefined)).toBe(true)
  })
  it('status 是字符串 "401" → 重试（严格类型判断，不做隐式转换）', () => {
    expect(defaultRetry(0, { status: '401' })).toBe(true)
  })
})
