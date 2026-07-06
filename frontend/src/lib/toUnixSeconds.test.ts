import { describe, it, expect } from 'vitest'
import { toUnixSeconds } from './toUnixSeconds'

describe('toUnixSeconds', () => {
  it('ISO-8601 Z 字符串 → Unix 秒(= Date.parse/1000)', () => {
    const iso = '2026-06-15T08:30:00Z'
    expect(toUnixSeconds(iso)).toBe(Math.floor(Date.parse(iso) / 1000))
  })

  it('秒精度(非毫秒)', () => {
    const iso = '2026-06-15T08:30:45Z'
    expect(toUnixSeconds(iso)).toBe(Math.floor(Date.parse(iso) / 1000))
    // 秒级,非毫秒
    expect(toUnixSeconds(iso)).not.toBe(Date.parse(iso))
  })

  it('同 ISO 多次调用结果一致(纯函数)', () => {
    const iso = '2026-07-01T00:00:00Z'
    expect(toUnixSeconds(iso)).toBe(toUnixSeconds(iso))
  })

  it('无效字符串抛错(不静默返 NaN)', () => {
    expect(() => toUnixSeconds('not-a-date')).toThrow()
  })

  it('空字符串抛错', () => {
    expect(() => toUnixSeconds('')).toThrow()
  })
})
