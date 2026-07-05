import { describe, it, expect } from 'vitest'
import { nextDelay, MAX_BACKOFF_MS } from './nextDelay'

describe('nextDelay', () => {
  it('attempt 0 → 1000(1s)', () => {
    expect(nextDelay(0)).toBe(1_000)
  })
  it('attempt 1 → 2000(2s)', () => {
    expect(nextDelay(1)).toBe(2_000)
  })
  it('attempt 2 → 5000(5s)', () => {
    expect(nextDelay(2)).toBe(5_000)
  })
  it('attempt 3 → 10000(10s)', () => {
    expect(nextDelay(3)).toBe(10_000)
  })
  it('attempt 4 → 30000(30s 上限)', () => {
    expect(nextDelay(4)).toBe(30_000)
  })
  it('attempt 超出序列 → 固定 30s', () => {
    expect(nextDelay(5)).toBe(MAX_BACKOFF_MS)
    expect(nextDelay(100)).toBe(MAX_BACKOFF_MS)
  })
  it('负数 attempt 兜底返首项', () => {
    expect(nextDelay(-1)).toBe(1_000)
  })
})
